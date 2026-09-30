package com.reconciliation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.reconciliation.model.CanonicalTransactionRecord;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
public class RedisMatchingStateRepository {

    private static final String GATEWAY_PREFIX = "recon:gateway:";
    private static final String BANK_PREFIX = "recon:bank:";
    private static final String RECONCILED_KEY = "recon:reconciled";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisMatchingStateRepository(
        RedisTemplate<String, String> redisTemplate) {

            this.redisTemplate = redisTemplate;
            this.objectMapper = new ObjectMapper();
            this.objectMapper.findAndRegisterModules();
    }

    public void saveGatewayTransaction(
            CanonicalTransactionRecord transaction) {

        save(GATEWAY_PREFIX, transaction);
    }

    public void saveBankTransaction(
            CanonicalTransactionRecord transaction) {

        save(BANK_PREFIX, transaction);
    }

    public CanonicalTransactionRecord getGatewayTransaction(
            String eventId) {

        return get(GATEWAY_PREFIX, eventId);
    }

    public CanonicalTransactionRecord getBankTransaction(
            String eventId) {

        return get(BANK_PREFIX, eventId);
    }

    public List<CanonicalTransactionRecord> getAllGatewayTransactions() {

        return getAll(GATEWAY_PREFIX);
    }

    public List<CanonicalTransactionRecord> getAllBankTransactions() {

        return getAll(BANK_PREFIX);
    }

    public void deleteGatewayTransaction(String eventId) {

        if (eventId != null) {
            redisTemplate.delete(GATEWAY_PREFIX + eventId);
        }
    }

    public void deleteBankTransaction(String eventId) {

        if (eventId != null) {
            redisTemplate.delete(BANK_PREFIX + eventId);
        }
    }

    public boolean gatewayTransactionExists(String eventId) {

        if (eventId == null) {
            return false;
        }

        return Boolean.TRUE.equals(
                redisTemplate.hasKey(GATEWAY_PREFIX + eventId)
        );
    }

    public boolean bankTransactionExists(String eventId) {

        if (eventId == null) {
            return false;
        }

        return Boolean.TRUE.equals(
                redisTemplate.hasKey(BANK_PREFIX + eventId)
        );
    }

    private void save(
            String prefix,
            CanonicalTransactionRecord transaction) {

        if (transaction == null ||
                transaction.getEventId() == null) {
            return;
        }

        try {

            String json =
                    objectMapper.writeValueAsString(transaction);

            redisTemplate.opsForValue().set(
                    prefix + transaction.getEventId(),
                    json
            );

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "Failed to serialize transaction",
                    e
            );
        }
    }

    private CanonicalTransactionRecord get(
            String prefix,
            String eventId) {

        if (eventId == null) {
            return null;
        }

        String json =
                redisTemplate.opsForValue().get(
                        prefix + eventId
                );

        if (json == null) {
            return null;
        }

        try {

            return objectMapper.readValue(
                    json,
                    CanonicalTransactionRecord.class
            );

        } catch (JsonProcessingException e) {

            throw new IllegalStateException(
                    "Failed to deserialize transaction",
                    e
            );
        }
    }

    private List<CanonicalTransactionRecord> getAll(
            String prefix) {

        List<CanonicalTransactionRecord> transactions =
                new ArrayList<>();

        Set<String> keys =
                redisTemplate.keys(prefix + "*");

        if (keys == null || keys.isEmpty()) {
            return transactions;
        }

        for (String key : keys) {

            String json =
                    redisTemplate.opsForValue().get(key);

            if (json == null) {
                continue;
            }

            try {

                transactions.add(
                        objectMapper.readValue(
                                json,
                                CanonicalTransactionRecord.class
                        )
                );

            } catch (JsonProcessingException e) {

                throw new IllegalStateException(
                        "Failed to deserialize transaction",
                        e
                );
            }
        }

        return transactions;
    }

    public void markReconciled(String eventId) {

        if (eventId != null && !eventId.isBlank()) {
            redisTemplate.opsForSet()
                    .add(RECONCILED_KEY, eventId);
        }
    }

    public Set<String> getReconciledEventIds() {

        Set<String> ids =
                redisTemplate.opsForSet()
                        .members(RECONCILED_KEY);

        return ids == null
                ? Set.of()
                : ids;
    }

    public boolean isReconciled(String eventId) {

        if (eventId == null || eventId.isBlank()) {
            return false;
        }

        return Boolean.TRUE.equals(
                redisTemplate.opsForSet()
                        .isMember(RECONCILED_KEY, eventId)
        );
    }
}