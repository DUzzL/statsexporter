package de.duzzl.statsexporter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Loads the documented, comment-friendly statsexporter.toml configuration. */
public final class StatsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsExporterMod.MOD_ID);
    static final int DEFAULT_PORT = 8790;
    static final int DEFAULT_CACHE_INTERVAL_MINUTES = 10;
    static final String DEFAULT_ALLOWED_ORIGIN = "*";
    static final int MIN_CACHE_INTERVAL_MINUTES = 5;
    static final int MAX_CACHE_INTERVAL_MINUTES = 15;

    private final Path configPath;
    private volatile int port = DEFAULT_PORT;
    private volatile int cacheIntervalMinutes = DEFAULT_CACHE_INTERVAL_MINUTES;
    private volatile String allowedOrigin = DEFAULT_ALLOWED_ORIGIN;
    private volatile List<String> objectives = new ArrayList<>();
    private volatile boolean hideBannedPlayers;
    private volatile Dashboard dashboard = Dashboard.defaults();

    private StatsConfig(Path configPath) { this.configPath = configPath; }

    static StatsConfig load(Path configDir) {
        try { Files.createDirectories(configDir); } catch (IOException e) { LOGGER.warn("Could not create config directory '{}': {}", configDir, e.getMessage()); }
        Path path = configDir.resolve("statsexporter.toml");
        Path legacyPath = configDir.resolve("statsexporter.json");
        StatsConfig config = new StatsConfig(path);
        try {
            if (Files.exists(path)) {
                config.readToml();
                LOGGER.info("Loaded Stats Exporter config from {}", path);
            } else if (Files.exists(legacyPath)) {
                config.readLegacyJson(legacyPath);
                config.writeConfig();
                LOGGER.info("Migrated legacy config from {} to {}", legacyPath, path);
            } else {
                config.writeConfig();
                LOGGER.info("Created documented Stats Exporter config at {}", path);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read Stats Exporter config '{}', using defaults and rewriting: {}", path, e.getMessage());
            config.writeConfig();
        }
        LOGGER.info("Stats Exporter config: port={}, cacheIntervalMinutes={}, allowedOrigin='{}', objectives={}, hideBannedPlayers={}", config.port, config.cacheIntervalMinutes, config.allowedOrigin, config.objectives, config.hideBannedPlayers);
        return config;
    }

    private void readToml() throws IOException {
        TomlParseResult root = Toml.parse(Files.readString(configPath));
        if (root.hasErrors()) throw new IOException(root.errors().toString());
        Long portValue = root.getLong("port");
        port = validPort(portValue == null ? DEFAULT_PORT : portValue.intValue());
        Long interval = root.getLong("cacheIntervalMinutes");
        cacheIntervalMinutes = clamp(interval == null ? DEFAULT_CACHE_INTERVAL_MINUTES : interval.intValue());
        allowedOrigin = stringOr(root.getString("allowedOrigin"), DEFAULT_ALLOWED_ORIGIN);
        objectives = stringArray(root.getArray("objectives"));
        Boolean banned = root.getBoolean("hideBannedPlayers");
        hideBannedPlayers = banned != null && banned;
        dashboard = dashboard(root.getTable("dashboard"));
    }

    /** One-time compatibility path; the old JSON is deliberately left untouched. */
    private void readLegacyJson(Path legacyPath) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(legacyPath)).getAsJsonObject();
        port = validPort(parsePort(root.get("port")));
        cacheIntervalMinutes = clamp(root.has("cacheIntervalMinutes") ? root.get("cacheIntervalMinutes").getAsInt() : DEFAULT_CACHE_INTERVAL_MINUTES);
        allowedOrigin = root.has("allowedOrigin") ? root.get("allowedOrigin").getAsString() : DEFAULT_ALLOWED_ORIGIN;
        objectives = root.has("objectives") && root.get("objectives").isJsonArray() ? jsonArray(root.getAsJsonArray("objectives")) : List.of();
        hideBannedPlayers = root.has("hideBannedPlayers") && root.get("hideBannedPlayers").getAsBoolean();
        dashboard = jsonDashboard(root);
    }

    private void writeConfig() {
        String content = """
                # Stats Exporter configuration
                # Full setup guide: https://github.com/DUzzL/statsexporter#configuration

                # HTTP port for the bundled dashboard (/) and JSON API (/api/stats).
                port = %d

                # Minutes between live-scoreboard refreshes. Allowed range: 5 to 15.
                cacheIntervalMinutes = %d

                # Browser origin allowed to access the API. Use "*" for an open API.
                allowedOrigin = %s

                # Scoreboard objectives to export. Use /scoreboard objectives list to find names.
                objectives = %s

                # Whether banned players are hidden from API and dashboard results.
                hideBannedPlayers = %s

                [dashboard]
                # Heading on the bundled dashboard.
                title = %s
                # Objectives shown there. [] means every exported objective.
                visibleObjectives = %s
                # Objective used for ranking; empty means alphabetical player names.
                sortBy = %s
                # "desc" ranks high values first; use "asc" for low values first.
                sortDirection = %s

                [dashboard.labels]
                # Optional friendly labels, e.g. kills = "Kills"
                %s
                """.formatted(port, cacheIntervalMinutes, quote(allowedOrigin), array(objectives), hideBannedPlayers, quote(dashboard.title), array(dashboard.visibleObjectives), quote(dashboard.sortBy), quote(dashboard.sortDirection), labels(dashboard.labels));
        try { Files.writeString(configPath, content); } catch (IOException e) { LOGGER.warn("Could not write config file '{}': {}", configPath, e.getMessage()); }
    }

    private static Dashboard dashboard(TomlTable table) {
        if (table == null) return Dashboard.defaults();
        Map<String, String> labels = new LinkedHashMap<>();
        TomlTable labelTable = table.getTable("labels");
        if (labelTable != null) for (String key : labelTable.keySet()) { String value = labelTable.getString(key); if (value != null && !value.isBlank()) labels.put(key, value.trim()); }
        return new Dashboard(stringOr(table.getString("title"), "Server Statistics"), stringArray(table.getArray("visibleObjectives")), labels, stringOr(table.getString("sortBy"), ""), "asc".equalsIgnoreCase(table.getString("sortDirection")) ? "asc" : "desc");
    }

    private static Dashboard jsonDashboard(JsonObject root) {
        if (!root.has("dashboard") || !root.get("dashboard").isJsonObject()) return Dashboard.defaults();
        JsonObject value = root.getAsJsonObject("dashboard");
        Map<String, String> labels = new LinkedHashMap<>();
        if (value.has("labels") && value.get("labels").isJsonObject()) for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject("labels").entrySet()) labels.put(entry.getKey(), entry.getValue().getAsString());
        return new Dashboard(value.has("title") ? value.get("title").getAsString() : "Server Statistics", value.has("visibleObjectives") ? jsonArray(value.getAsJsonArray("visibleObjectives")) : List.of(), labels, value.has("sortBy") ? value.get("sortBy").getAsString() : "", value.has("sortDirection") && "asc".equalsIgnoreCase(value.get("sortDirection").getAsString()) ? "asc" : "desc");
    }

    private static int parsePort(JsonElement value) { try { return value == null ? DEFAULT_PORT : Integer.parseInt(value.getAsString().trim()); } catch (Exception ignored) { return DEFAULT_PORT; } }
    private static int validPort(int value) {
        if (value >= 1 && value <= 65535) return value;
        LOGGER.warn("Port {} is outside the valid range 1-65535. Using {} instead.", value, DEFAULT_PORT);
        return DEFAULT_PORT;
    }
    private static int clamp(int value) { return Math.max(MIN_CACHE_INTERVAL_MINUTES, Math.min(MAX_CACHE_INTERVAL_MINUTES, value)); }
    private static String stringOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static List<String> stringArray(TomlArray values) { List<String> result = new ArrayList<>(); if (values != null) for (int i = 0; i < values.size(); i++) { String value = values.getString(i); if (value != null && !value.isBlank()) result.add(value.trim()); } return result; }
    private static List<String> jsonArray(Iterable<JsonElement> values) { List<String> result = new ArrayList<>(); for (JsonElement value : values) if (!value.getAsString().isBlank()) result.add(value.getAsString().trim()); return result; }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String array(List<String> values) { return "[" + values.stream().map(StatsConfig::quote).collect(Collectors.joining(", ")) + "]"; }
    private static String labels(Map<String, String> values) { return values.entrySet().stream().map(entry -> entry.getKey() + " = " + quote(entry.getValue())).collect(Collectors.joining("\n")); }

    int port() { return port; }
    int cacheIntervalMinutes() { return cacheIntervalMinutes; }
    String allowedOrigin() { return allowedOrigin; }
    List<String> objectives() { return objectives; }
    boolean hideBannedPlayers() { return hideBannedPlayers; }
    Dashboard dashboard() { return dashboard; }

    static final class Dashboard {
        final String title; final List<String> visibleObjectives; final Map<String, String> labels; final String sortBy; final String sortDirection;
        Dashboard(String title, List<String> visibleObjectives, Map<String, String> labels, String sortBy, String sortDirection) { this.title = title; this.visibleObjectives = List.copyOf(visibleObjectives); this.labels = Map.copyOf(labels); this.sortBy = sortBy; this.sortDirection = sortDirection; }
        static Dashboard defaults() { return new Dashboard("Server Statistics", List.of(), Map.of(), "", "desc"); }
    }
}
