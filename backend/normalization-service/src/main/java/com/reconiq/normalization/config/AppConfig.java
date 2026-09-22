package com.reconiq.normalization.config;

import com.reconiq.normalization.ops.DlqGuard;
import com.reconiq.normalization.writer.ConflictPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record AppConfig(
        List<String> inputTopics,
        String canonicalTopic,
        String dlqTopic,
        String applicationId,
        String bootstrapServers,
        String schemaRegistryUrl,
        boolean createTopicsOnStartup,
        int topicPartitions,
        int topicReplication,
        AppMode appMode,
        String dbUrl,
        String dbUser,
        String dbPassword,
        String writerGroupId,
        int writerBatchSize,
        ConflictPolicy conflictPolicy,
        int healthPort,
        DlqGuard.Action guardAction,
        int guardWindowSeconds,
        int guardProcessingErrors,
        double guardRejectRatio,
        int guardMinRecords) {

    public enum AppMode {
        NORMALIZER, WRITER, ALL
    }

    /** Names follow the existing root .env.example convention (KAFKA_HOST/PORT, POSTGRES_*). */
    public static AppConfig fromEnv() {
        String kafkaHost = env("KAFKA_HOST", "localhost");
        String kafkaPort = env("KAFKA_PORT", "9092");
        String postgresHost = env("POSTGRES_HOST", "localhost");
        String postgresPort = env("POSTGRES_PORT", "5432");
        String postgresDb = env("POSTGRES_DB", "reconciliation");

        AppConfig config = new AppConfig(
                List.of(env("INPUT_TOPICS", "raw-transactions").split(",")),
                env("CANONICAL_TOPIC", "txn.canonical"),
                env("DLQ_TOPIC", "txn.canonical.dlq"),
                env("STREAMS_APPLICATION_ID", "normalization-service"),
                kafkaHost + ":" + kafkaPort,
                env("SCHEMA_REGISTRY_URL", "http://localhost:8081"),
                Boolean.parseBoolean(env("TOPICS_AUTO_CREATE", "true")),
                parseInt("TOPIC_PARTITIONS", 3),
                parseInt("TOPIC_REPLICATION", 1),
                parseEnum(AppMode.class, "APP_MODE", "all"),
                "jdbc:postgresql://" + postgresHost + ":" + postgresPort + "/" + postgresDb,
                env("POSTGRES_USER", "reconiq"),
                env("POSTGRES_PASSWORD", "reconiq_local_dev"),
                env("WRITER_GROUP_ID", "normalization-writer"),
                parseInt("WRITER_BATCH_SIZE", 500),
                parseEnum(ConflictPolicy.class, "WRITER_CONFLICT_POLICY", "REPORT"),
                parseInt("HEALTH_PORT", 8090),
                parseEnum(DlqGuard.Action.class, "DLQ_GUARD_ACTION", "alarm"),
                parseInt("DLQ_GUARD_WINDOW_SECONDS", 60),
                parseInt("DLQ_GUARD_PROCESSING_ERRORS", 5),
                parseDouble("DLQ_GUARD_REJECT_RATIO", 0.5),
                parseInt("DLQ_GUARD_MIN_RECORDS", 100));
        config.validate();
        return config;
    }

    /** Collects every problem before failing, so a fresh deployment sees all of them at once. Exit code 2. */
    private void validate() {
        List<String> problems = new ArrayList<>();
        if (inputTopics.isEmpty() || inputTopics.stream().anyMatch(String::isBlank)) {
            problems.add("INPUT_TOPICS must list at least one non-blank topic name");
        }
        if (canonicalTopic.isBlank()) {
            problems.add("CANONICAL_TOPIC must not be blank");
        }
        if (dlqTopic.isBlank()) {
            problems.add("DLQ_TOPIC must not be blank");
        }
        if (healthPort < 0 || healthPort > 65535) {
            problems.add("HEALTH_PORT must be 0 (disabled) or a valid port number");
        }
        if (guardRejectRatio < 0 || guardRejectRatio > 1) {
            problems.add("DLQ_GUARD_REJECT_RATIO must be between 0 and 1");
        }
        if (topicPartitions < 1) {
            problems.add("TOPIC_PARTITIONS must be at least 1");
        }
        if ((appMode == AppMode.WRITER || appMode == AppMode.ALL)
                && (dbUrl.isBlank() || dbUser.isBlank())) {
            problems.add("POSTGRES_HOST/POSTGRES_DB and POSTGRES_USER are required when APP_MODE is writer or all");
        }
        if (!problems.isEmpty()) {
            System.err.println("Configuration error(s):");
            problems.forEach(p -> System.err.println("  - " + p));
            System.exit(2);
        }
    }

    /** Startup summary with the password masked -- safe to log at INFO. */
    public String summary() {
        return "mode=" + appMode + " inputTopics=" + inputTopics + " canonicalTopic=" + canonicalTopic
                + " dlqTopic=" + dlqTopic + " bootstrapServers=" + bootstrapServers
                + " schemaRegistryUrl=" + schemaRegistryUrl + " dbUrl=" + dbUrl + " dbUser=" + dbUser
                + " dbPassword=***** conflictPolicy=" + conflictPolicy + " healthPort=" + healthPort
                + " guardAction=" + guardAction;
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static int parseInt(String key, int defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : Integer.parseInt(value.trim());
    }

    private static double parseDouble(String key, double defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : Double.parseDouble(value.trim());
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String key, String defaultValue) {
        return Enum.valueOf(type, env(key, defaultValue).toUpperCase(Locale.ROOT));
    }
}
