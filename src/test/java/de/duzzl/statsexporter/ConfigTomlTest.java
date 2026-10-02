package de.duzzl.statsexporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigTomlTest {
    @TempDir Path configDirectory;

    @Test
    void generatedConfigCanBeRead() throws IOException {
        StatsConfig.load(configDirectory);
        StatsConfig loaded = StatsConfig.load(configDirectory);
        assertEquals(8790, loaded.port());
        assertEquals("*", loaded.allowedOrigin());
        assertEquals(List.of(), loaded.objectives());
        assertEquals(true, Files.readString(configDirectory.resolve("statsexporter.toml")).contains("[dashboard.labels]"));
    }

    @Test
    void migratesLegacyJson() throws IOException {
        Files.writeString(configDirectory.resolve("statsexporter.json"), """
                {"port": 8844, "objectives": ["kills"], "hideBannedPlayers": true}
                """);
        StatsConfig migrated = StatsConfig.load(configDirectory);
        assertEquals(8844, migrated.port());
        assertEquals(List.of("kills"), migrated.objectives());
        StatsConfig reloaded = StatsConfig.load(configDirectory);
        assertEquals(8844, reloaded.port());
        assertEquals(true, reloaded.hideBannedPlayers());
    }

    @Test
    void readsDocumentedConfiguration() throws IOException {
        Map<String, Object> config = ConfigToml.parse("""
                port = 8790 # HTTP port
                cacheIntervalMinutes = 10
                allowedOrigin = "https://stats.example.com"
                objectives = [
                  "kills", # count
                  "play,time",
                ]
                hideBannedPlayers = true

                [dashboard]
                title = "Stats \\"today\\""
                visibleObjectives = ['kills']
                sortBy = "kills"
                sortDirection = "desc"

                [dashboard.labels]
                "play,time" = "Play \\u00E9 time"
                """
        );
        assertEquals(8790L, config.get("port"));
        assertEquals(List.of("kills", "play,time"), config.get("objectives"));
        assertEquals(true, config.get("hideBannedPlayers"));
        @SuppressWarnings("unchecked") Map<String, Object> dashboard = (Map<String, Object>) config.get("dashboard");
        assertEquals("Stats \"today\"", dashboard.get("title"));
        @SuppressWarnings("unchecked") Map<String, Object> labels = (Map<String, Object>) dashboard.get("labels");
        assertEquals("Play é time", labels.get("play,time"));
    }

    @Test
    void rejectsMalformedConfiguration() {
        assertThrows(IOException.class, () -> ConfigToml.parse("objectives = [\"kills\""));
        assertThrows(IOException.class, () -> ConfigToml.parse("port = 123\nport = 456"));
        assertThrows(IOException.class, () -> ConfigToml.parse("title = \"broken \\q\""));
    }
}
