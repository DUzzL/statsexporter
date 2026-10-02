package de.duzzl.statsexporter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsHttpServerTest {
    @TempDir Path configDirectory;

    @Test
    void servesDashboardAndApi() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Files.writeString(configDirectory.resolve("statsexporter.toml"), """
                port = %d
                allowedOrigin = "https://stats.example.com"
                [dashboard]
                title = "Test statistics"
                """.formatted(port));
        StatsConfig config = StatsConfig.load(configDirectory);
        StatsHttpServer server = new StatsHttpServer(config, new ScoreboardReader(null, config));
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI base = URI.create("http://127.0.0.1:" + port);
            HttpResponse<String> api = null;
            for (int attempt = 0; attempt < 40; attempt++) {
                try { api = get(client, base.resolve("/api/stats")); break; }
                catch (java.net.ConnectException e) { Thread.sleep(50); }
            }
            assertEquals(200, api.statusCode());
            assertTrue(api.body().contains("\"players\":[]"));
            assertTrue(api.body().contains("Test statistics"));
            assertEquals("https://stats.example.com", api.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertEquals(200, get(client, base.resolve("/")).statusCode());
            assertEquals(200, get(client, base.resolve("/app.js")).statusCode());
            assertEquals(200, get(client, base.resolve("/styles.css")).statusCode());
            assertEquals(404, get(client, base.resolve("/missing")).statusCode());
            HttpRequest post = HttpRequest.newBuilder(base.resolve("/api/stats"))
                    .POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(3)).build();
            assertEquals(405, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
