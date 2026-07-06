package com.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight embedded HTTP server that exposes the cached player stats
 * snapshot at {@code GET /api/stats}.
 *
 * The snapshot is recomputed on a fixed schedule (every cacheIntervalMinutes)
 * and HTTP requests serve the cached, pre-encoded JSON bytes. All of the
 * mod's own work — JSON serialization, encoding, request handling — runs on
 * daemon threads at {@link Thread#MIN_PRIORITY} so it yields to the server
 * threads under CPU contention. The only step that runs on the server thread
 * is the scoreboard snapshot copy itself (see
 * {@link ScoreboardReader#snapshot()}), which is required for thread safety
 * and costs well under a millisecond.
 *
 * CORS is configured to allow the configured website origin to fetch the
 * endpoint cross-origin.
 */
public final class StatsHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(StatsExporterMod.MOD_ID);

    // Compact (non-pretty) JSON: smaller responses, less garbage per refresh.
    private static final Gson GSON = new Gson();

    // How long a refresh waits for the server thread to hand over the
    // scoreboard snapshot before giving up and keeping the previous snapshot.
    private static final int SNAPSHOT_TIMEOUT_SECONDS = 30;

    private final StatsConfig config;
    private final ScoreboardReader reader;

    // The response body served to every request, pre-encoded as UTF-8 so
    // request handling allocates (almost) nothing.
    private final AtomicReference<byte[]> cachedBody = new AtomicReference<>(new byte[0]);

    // Simple per-second rate limiter: allows up to MAX_REQUESTS_PER_SECOND
    // requests per second before returning 429 Too Many Requests. The window
    // is derived from the clock rather than reset by a scheduled task, so it
    // stays accurate even when the mod's low-priority threads are starved.
    private static final int MAX_REQUESTS_PER_SECOND = 30;
    private final AtomicLong rateWindow = new AtomicLong(Long.MIN_VALUE);
    private final AtomicInteger requestCounter = new AtomicInteger(0);

    private HttpServer server;
    private ExecutorService httpPool;
    private ScheduledExecutorService scheduler;

    StatsHttpServer(StatsConfig config, ScoreboardReader reader) {
        this.config = config;
        this.reader = reader;
    }

    /** Start the HTTP server and schedule the periodic cache refresh. */
    void start() {
        // Serve a valid empty snapshot until the first refresh lands; the
        // first refresh itself runs asynchronously (below) so server startup
        // is never blocked on a scoreboard read.
        cachedBody.set(buildJson(Map.of()).getBytes(StandardCharsets.UTF_8));

        try {
            server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        } catch (IOException e) {
            LOGGER.error("Failed to bind Stats Exporter HTTP server on port {}: {}", config.port(), e.getMessage());
            LOGGER.error("Make sure the port is open as an additional allocation in your Pterodactyl/Folium panel.");
            return;
        }

        server.createContext("/api/stats", new StatsHandler());
        httpPool = Executors.newFixedThreadPool(2, lowPriorityFactory("statsexporter-http"));
        server.setExecutor(httpPool);
        server.start();
        LOGGER.info("Stats Exporter HTTP server listening on port {} (endpoint: GET /api/stats)", config.port());

        // Refresh the cache now, then again after every configured interval.
        // Fixed delay rather than fixed rate: the refresh thread runs at
        // minimum priority and may be starved on a busy host, and catch-up
        // runs of a cache refresh are never useful.
        scheduler = Executors.newSingleThreadScheduledExecutor(lowPriorityFactory("statsexporter-cache"));
        scheduler.scheduleWithFixedDelay(this::refreshCache, 0,
                config.cacheIntervalMinutes(), TimeUnit.MINUTES);

        LOGGER.info("Stats cache will refresh every {} minute(s)", config.cacheIntervalMinutes());
    }

    /** Stop the HTTP server, its worker pool and the cache scheduler. */
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (server != null) {
            server.stop(0);
            LOGGER.info("Stats Exporter HTTP server stopped");
        }
        if (httpPool != null) {
            // HttpServer.stop() does not shut down a user-supplied executor.
            httpPool.shutdownNow();
        }
    }

    /**
     * All threads owned by the mod are daemon threads at minimum priority so
     * they never keep the JVM alive and always lose CPU contention against
     * the server threads. (On Linux the JVM only honors thread priorities
     * when started with -XX:ThreadPriorityPolicy=1 — see the "Performance
     * notes" section of the README.)
     */
    private static ThreadFactory lowPriorityFactory(String baseName) {
        AtomicInteger count = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, baseName + "-" + count.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    /**
     * Recompute the stats snapshot and cache it as pre-encoded JSON bytes.
     *
     * Runs on the low-priority cache thread. The scoreboard data is copied on
     * the server thread (the only thread that may touch it), then serialized
     * and encoded here so the expensive part can never stall a tick.
     */
    private void refreshCache() {
        Map<String, ScoreboardReader.PlayerStats> data;
        try {
            data = reader.snapshot().get(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutting down — keep the old snapshot
            return;
        } catch (TimeoutException e) {
            LOGGER.warn("Scoreboard snapshot not produced within {}s (server thread busy?); keeping previous snapshot",
                    SNAPSHOT_TIMEOUT_SECONDS);
            return;
        } catch (Throwable t) {
            // Never let a cache refresh crash the scheduler thread.
            LOGGER.error("Failed to snapshot the scoreboard: {}", t.getMessage());
            return;
        }

        try {
            cachedBody.set(buildJson(data).getBytes(StandardCharsets.UTF_8));
            LOGGER.info("Refreshed stats cache: {} player(s)", data.size());
        } catch (Throwable t) {
            LOGGER.error("Failed to refresh stats cache: {}", t.getMessage());
        }
    }

    /** Build the JSON response string from the given scoreboard snapshot. */
    private String buildJson(Map<String, ScoreboardReader.PlayerStats> data) {
        // Build a JsonObject per player so the objective field names come
        // directly from the config (they are not known at compile time).
        List<JsonObject> players = data.values().stream()
                .map(this::playerToJson)
                .toList();

        StatsJson root = new StatsJson(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                players);
        return GSON.toJson(root);
    }

    /** Convert one player's stats to a JSON object with dynamic objective fields. */
    private JsonObject playerToJson(ScoreboardReader.PlayerStats p) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", p.name);
        for (Map.Entry<String, Integer> entry : p.scores.entrySet()) {
            obj.addProperty(entry.getKey(), entry.getValue());
        }
        return obj;
    }

    /**
     * Count one request against the current one-second window. Races on a
     * window boundary can at worst let a few extra requests through —
     * acceptable for a soft limit.
     */
    private boolean tryAcquireRequestSlot() {
        long window = System.nanoTime() / 1_000_000_000L;
        long previous = rateWindow.get();
        if (previous != window && rateWindow.compareAndSet(previous, window)) {
            requestCounter.set(0);
        }
        return requestCounter.incrementAndGet() <= MAX_REQUESTS_PER_SECOND;
    }

    /** HTTP handler for GET /api/stats — serves the cached JSON snapshot. */
    private final class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Only GET is supported; respond to OPTIONS for CORS preflight.
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                applyCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                applyCorsHeaders(exchange);
                byte[] body = "Method Not Allowed\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(405, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                exchange.close();
                return;
            }

            // Rate limiting: reject requests beyond the per-second cap.
            if (!tryAcquireRequestSlot()) {
                applyCorsHeaders(exchange);
                byte[] body = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(429, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
                exchange.close();
                return;
            }

            byte[] body = cachedBody.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            applyCorsHeaders(exchange);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        }

        /** Apply CORS response headers. Must be called before sendResponseHeaders. */
        private void applyCorsHeaders(HttpExchange exchange) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", config.allowedOrigin());
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
        }
    }

    // ── JSON DTOs ──────────────────────────────────────────────────────

    /** Top-level JSON object. */
    private static final class StatsJson {
        final String lastUpdated;
        final List<JsonObject> players;

        StatsJson(String lastUpdated, List<JsonObject> players) {
            this.lastUpdated = lastUpdated;
            this.players = players;
        }
    }
}
