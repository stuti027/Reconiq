package com.reconiq.reporting.common;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness check for the frontend: answers without touching the database. */
@RestController
public class PingController {

    @GetMapping("/api/reporting/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }
}
