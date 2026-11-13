package com.reconiq.normalization.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exposes /health (200 UP, 503 DEGRADED/DOWN) and /metrics (JSON counters) using only the JDK's
 * built-in HTTP server -- no extra library. Nothing sensitive is exposed.
 */
public class HealthServer {

    private static final Logger log = LoggerFactory.getLogger(HealthServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration WRITER_HEARTBEAT_STALE_AFTER = Duration.ofMinutes(2);

    private final HttpServer server;

    public HealthServer(int port, String appMode, Metrics metrics, DlqGuard dlqGuard,
            Supplier<String> streamsStateSupplier, Supplier<Instant> writerHeartbeatSupplier,
            Supplier<Instant> lastDbCommitSupplier) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/health", exchange -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", appMode);

            boolean up = true;
            if (streamsStateSupplier != null) {
                String state = streamsStateSupplier.get();
                body.put("streams_state", state);
                up = up && ("RUNNING".equals(state) || "REBALANCING".equals(state));
            }
            if (writerHeartbeatSupplier != null) {
                Instant heartbeat = writerHeartbeatSupplier.get();
                body.put("writer_heartbeat", String.valueOf(heartbeat));
                boolean writerAlive = heartbeat != null
                        && Duration.between(heartbeat, Instant.now()).compareTo(WRITER_HEARTBEAT_STALE_AFTER) < 0;
                up = up && writerAlive;
            }
            if (lastDbCommitSupplier != null) {
                body.put("last_db_commit", String.valueOf(lastDbCommitSupplier.get()));
            }

            boolean degraded = dlqGuard != null && dlqGuard.isTripped();
            body.put("guard_tripped", degraded);

            String status = !up ? "DOWN" : (degraded ? "DEGRADED" : "UP");
            body.put("status", status);

            int statusCode = "UP".equals(status) ? 200 : 503;
            respond(exchange, statusCode, body);
        });

        server.createContext("/metrics", exchange -> respond(exchange, 200, metrics.snapshot()));

        server.setExecutor(null);
    }

    public void start() {
        server.start();
        log.info("Health server listening on port {}", server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int statusCode, Map<String, Object> body)
            throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes, 0, bytes.length);
        }
    }
}
