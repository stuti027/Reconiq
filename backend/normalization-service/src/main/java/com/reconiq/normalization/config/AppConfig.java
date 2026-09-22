package com.reconiq.normalization.config;

import com.reconiq.normalization.writer.ConflictPolicy;
import java.util.List;

public record AppConfig(
        List<String> inputTopics,
        String canonicalTopic,
        String dlqTopic,
        String applicationId,
        String bootstrapServers,
        String schemaRegistryUrl,
        boolean createTopicsOnStartup,
        AppMode appMode,
        String dbUrl,
        String dbUser,
        String dbPassword,
        ConflictPolicy conflictPolicy) {

    public enum AppMode {
        NORMALIZER, WRITER, ALL
    }

    public static AppConfig fromEnv() {
        return new AppConfig(
                List.of(env("INPUT_TOPICS", "raw-transactions").split(",")),
                env("CANONICAL_TOPIC", "txn.canonical"),
                env("DLQ_TOPIC", "txn.canonical.dlq"),
                env("APPLICATION_ID", "normalization-service"),
                env("BOOTSTRAP_SERVERS", "localhost:9092"),
                env("SCHEMA_REGISTRY_URL", "http://localhost:8081"),
                Boolean.parseBoolean(env("CREATE_TOPICS_ON_STARTUP", "true")),
                AppMode.valueOf(env("APP_MODE", "all").toUpperCase(java.util.Locale.ROOT)),
                env("DB_URL", "jdbc:postgresql://localhost:5432/reconciliation"),
                env("DB_USER", "reconiq"),
                env("DB_PASSWORD", "reconiq_local_dev"),
                ConflictPolicy.valueOf(env("WRITER_CONFLICT_POLICY", "REPORT").toUpperCase(java.util.Locale.ROOT)));
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
