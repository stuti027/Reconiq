package com.reconiq.normalization.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HealthServerTest {

    private HealthServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthReturns200WhenEverythingIsUp() throws Exception {
        server = new HealthServer(0, "all", new Metrics(), null,
                () -> "RUNNING", () -> Instant.now(), () -> Instant.now());
        server.start();

        var response = get("/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").contains("\"mode\":\"all\"");
    }

    @Test
    void healthReturns503WhenStreamsIsNotRunning() throws Exception {
        server = new HealthServer(0, "normalizer", new Metrics(), null,
                () -> "ERROR", null, null);
        server.start();

        var response = get("/health");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DOWN\"");
    }

    @Test
    void healthReturns503WhenGuardIsTripped() throws Exception {
        DlqGuard guard = new DlqGuard(java.time.Duration.ofSeconds(60), 1, 0.99, 999, DlqGuard.Action.ALARM, null);
        guard.recordOutcome(true, "PROCESSING_ERROR");

        server = new HealthServer(0, "all", new Metrics(), guard, () -> "RUNNING", () -> Instant.now(), () -> Instant.now());
        server.start();

        var response = get("/health");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DEGRADED\"").contains("\"guard_tripped\":true");
    }

    @Test
    void metricsReturnsJsonSnapshot() throws Exception {
        Metrics metrics = new Metrics();
        metrics.incCanonicalOut();
        server = new HealthServer(0, "all", metrics, null, () -> "RUNNING", () -> Instant.now(), () -> Instant.now());
        server.start();

        var response = get("/metrics");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"canonical_out\":1");
    }
}
