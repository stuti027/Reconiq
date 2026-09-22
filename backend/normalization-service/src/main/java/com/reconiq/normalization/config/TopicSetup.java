package com.reconiq.normalization.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TopicSetup {

    private static final Logger log = LoggerFactory.getLogger(TopicSetup.class);
    private static final int PARTITIONS = 3;
    private static final short REPLICATION_FACTOR = 1;

    private TopicSetup() {
    }

    public static void ensureTopicsExist(AppConfig config) {
        Set<String> desired = new HashSet<>(config.inputTopics());
        desired.add(config.canonicalTopic());
        desired.add(config.dlqTopic());

        try (Admin admin = Admin.create(Map.of("bootstrap.servers", config.bootstrapServers()))) {
            List<NewTopic> newTopics = desired.stream()
                    .map(topic -> new NewTopic(topic, PARTITIONS, REPLICATION_FACTOR))
                    .toList();
            admin.createTopics(newTopics).values().forEach((name, future) -> {
                try {
                    future.get();
                    log.info("Created topic {}", name);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof TopicExistsException) {
                        log.debug("Topic {} already exists", name);
                    } else {
                        throw new IllegalStateException("Failed to create topic " + name, e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while creating topic " + name, e);
                }
            });
        }
    }
}
