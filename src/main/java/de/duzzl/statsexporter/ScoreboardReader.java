package de.duzzl.statsexporter;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Reads player statistics directly from the running server's in-memory
 * {@link Scoreboard} via {@link MinecraftServer#getScoreboard()}.
 *
 * This intentionally avoids parsing scoreboard.dat as NBT — because the mod
 * runs server-side, it has live access to the scoreboard object and can read
 * the current values without touching disk.
 *
 * The objectives to expose are configurable via {@link StatsConfig#objectives()}.
 * If {@link StatsConfig#hideBannedPlayers()} is true, banned players are
 * excluded from the result.
 *
 * Thread safety: the scoreboard and the ban list are mutated by the server
 * thread, so they may only be read from the server thread. {@link #snapshot()}
 * takes care of that — it copies the scores into a plain map on the server
 * thread and hands the copy to the caller, which can then do all expensive
 * work (JSON serialization) on its own thread.
 */
public final class ScoreboardReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsExporterMod.MOD_ID);

    private final MinecraftServer server;
    private final StatsConfig config;

    ScoreboardReader(MinecraftServer server, StatsConfig config) {
        this.server = server;
        this.config = config;
    }

    /**
     * Snapshot the configured objectives into a plain map, running the actual
     * scoreboard read on the server thread (the only thread that may touch
     * live server data). The read is a cheap copy of player names and int
     * scores, so the time spent on the server thread is negligible — well
     * under a millisecond even for thousands of tracked players.
     *
     * If called on the server thread itself, {@code server.execute} runs the
     * task inline and the returned future is already complete.
     *
     * @return a future completed on the server thread with the copied stats;
     *         the map is exclusively owned by the caller
     */
    CompletableFuture<Map<String, PlayerStats>> snapshot() {
        CompletableFuture<Map<String, PlayerStats>> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(read());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Build a map of player name -> stats for all players that have at least
     * one of the configured objectives set.
     *
     * Must run on the server thread — use {@link #snapshot()} from anywhere else.
     *
     * @return an ordered map (player name -> stats); empty if no data exists
     */
    private Map<String, PlayerStats> read() {
        Map<String, PlayerStats> result = new LinkedHashMap<>();
        Scoreboard scoreboard = server.getScoreboard();
        if (scoreboard == null) {
            // No scoreboard yet — return empty rather than erroring.
            return result;
        }

        // Collect scores for each configured objective independently, then
        // merge by player name. A player may have some objectives but not others.
        for (String objectiveName : config.objectives()) {
            collectObjective(scoreboard, objectiveName, result);
        }

        // Optionally remove banned players from the result.
        if (config.hideBannedPlayers() && !result.isEmpty()) {
            removeBannedPlayers(result);
        }

        return result;
    }

    /**
     * Pull every player's score for a single objective and merge it into the
     * result map. Uses {@link Scoreboard#listPlayerScores(Objective)} which
     * returns one {@link PlayerScoreEntry} per tracked player.
     */
    private void collectObjective(Scoreboard scoreboard, String objectiveName,
                                  Map<String, PlayerStats> result) {
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) {
            // Objective not registered yet — nothing to read for this metric.
            return;
        }

        try {
            for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
                String name = entry.owner();
                if (name == null || name.isBlank()) {
                    continue;
                }
                int value = entry.value();

                PlayerStats stats = result.computeIfAbsent(name, PlayerStats::new);
                stats.scores.put(objectiveName, value);
            }
        } catch (Throwable t) {
            // Defensive: never let a scoreboard read crash the cache refresh.
            LOGGER.warn("Failed to read objective '{}': {}", objectiveName, t.getMessage());
        }
    }

    /**
     * Remove banned players from the result map. Collects the banned player
     * names from the server's {@link UserBanList} and removes any matching
     * entry. Comparing by name (rather than UUID) is reliable here because
     * the scoreboard also keys on player names.
     */
    private void removeBannedPlayers(Map<String, PlayerStats> result) {
        try {
            PlayerList playerList = server.getPlayerList();
            if (playerList == null) {
                return;
            }
            UserBanList banList = playerList.getBans();
            if (banList == null || banList.isEmpty()) {
                return;
            }
            // Collect the lowercase names of all banned players so we can
            // do a case-insensitive comparison without creating NameAndId
            // objects (which would generate offline UUIDs that don't match
            // online-mode bans).
            Set<String> bannedNames = new HashSet<>();
            for (StoredUserEntry<NameAndId> entry : banList.getEntries()) {
                NameAndId nameAndId = entry.getUser();
                if (nameAndId != null && nameAndId.name() != null) {
                    bannedNames.add(nameAndId.name().toLowerCase());
                }
            }
            if (bannedNames.isEmpty()) {
                return;
            }
            result.entrySet().removeIf(entry ->
                    bannedNames.contains(entry.getKey().toLowerCase()));
        } catch (Throwable t) {
            LOGGER.warn("Failed to filter banned players: {}", t.getMessage());
        }
    }

    /** Simple holder for one player's exported stats. */
    static final class PlayerStats {
        final String name;
        final Map<String, Integer> scores = new LinkedHashMap<>();

        PlayerStats(String name) {
            this.name = name;
        }
    }
}
