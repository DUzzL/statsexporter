package de.duzzl.statsexporter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Stats Exporter — a server-side Fabric mod that exposes player scoreboard
 * statistics over a lightweight embedded HTTP endpoint and bundled dashboard.
 *
 * The mod does not run on the client (see fabric.mod.json environment=server).
 */
public final class StatsExporterMod implements ModInitializer {

    public static final String MOD_ID = "statsexporter";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static StatsHttpServer httpServer;

    @Override
    public void onInitialize() {
        LOGGER.info("Stats Exporter initializing — will start HTTP endpoint on server start");

        // Start the HTTP server once the server is fully started (world + scoreboard loaded).
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);

        // Stop the HTTP server cleanly on shutdown.
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
    }

    private void onServerStarted(MinecraftServer server) {
        try {
            Path configDir = server.getServerDirectory().resolve("config");
            StatsConfig config = StatsConfig.load(configDir);
            ScoreboardReader reader = new ScoreboardReader(server, config);

            httpServer = new StatsHttpServer(config, reader);
            httpServer.start();
        } catch (Throwable t) {
            LOGGER.error("Failed to start Stats Exporter HTTP server: {}", t.getMessage(), t);
        }
    }

    private void onServerStopping(MinecraftServer server) {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }
}
