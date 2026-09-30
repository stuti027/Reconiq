package com.reconciliation.service;

import com.reconciliation.model.CanonicalTransactionRecord;
import com.reconciliation.repository.RedisMatchingStateRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisMatchingStateRepositoryTest {

    private RedisTemplate<String, String> redisTemplate;

    private ValueOperations<String, String> valueOperations;

    private RedisMatchingStateRepository repository;

    @BeforeEach
    void setUp() {

        redisTemplate = mock(RedisTemplate.class);

        valueOperations = mock(ValueOperations.class);

        when(redisTemplate.opsForValue())
                .thenReturn(valueOperations);

        repository =
                new RedisMatchingStateRepository(redisTemplate);
    }

    @Test
    void shouldSaveAndRetrieveGatewayTransaction() {

        CanonicalTransactionRecord transaction =
                transaction(
                        "REDIS-G-001",
                        "TXN-REDIS-001",
                        "PAYMENT_GATEWAY",
                        "REF-REDIS-001",
                        "1000.00"
                );

        repository.saveGatewayTransaction(transaction);

        verify(valueOperations).set(
                eq("recon:gateway:REDIS-G-001"),
                anyString()
        );

        String storedJson =
                capturedGatewayJson(transaction);

        when(valueOperations.get(
                "recon:gateway:REDIS-G-001"))
                .thenReturn(storedJson);

        CanonicalTransactionRecord retrieved =
                repository.getGatewayTransaction(
                        "REDIS-G-001"
                );

        assertNotNull(retrieved);

        assertEquals(
                "REDIS-G-001",
                retrieved.getEventId()
        );

        assertEquals(
                "TXN-REDIS-001",
                retrieved.getTransactionId()
        );

        assertEquals(
                "PAYMENT_GATEWAY",
                retrieved.getSource()
        );

        assertEquals(
                "REF-REDIS-001",
                retrieved.getReferenceId()
        );

        assertEquals(
                new BigDecimal("1000.00"),
                retrieved.getAmount()
        );

        assertEquals(
                "INR",
                retrieved.getCurrency()
        );
    }

    @Test
    void shouldSaveAndRetrieveBankTransaction() {

        CanonicalTransactionRecord transaction =
                transaction(
                        "REDIS-B-001",
                        "TXN-REDIS-002",
                        "BANK",
                        "REF-REDIS-002",
                        "2500.00"
                );

        repository.saveBankTransaction(transaction);

        verify(valueOperations).set(
                eq("recon:bank:REDIS-B-001"),
                anyString()
        );

        String storedJson =
                capturedBankJson(transaction);

        when(valueOperations.get(
                "recon:bank:REDIS-B-001"))
                .thenReturn(storedJson);

        CanonicalTransactionRecord retrieved =
                repository.getBankTransaction(
                        "REDIS-B-001"
                );

        assertNotNull(retrieved);

        assertEquals(
                "REDIS-B-001",
                retrieved.getEventId()
        );

        assertEquals(
                "TXN-REDIS-002",
                retrieved.getTransactionId()
        );

        assertEquals(
                "BANK",
                retrieved.getSource()
        );

        assertEquals(
                "REF-REDIS-002",
                retrieved.getReferenceId()
        );

        assertEquals(
                new BigDecimal("2500.00"),
                retrieved.getAmount()
        );
    }

    @Test
    void shouldReturnNullWhenTransactionDoesNotExist() {

        when(valueOperations.get(
                "recon:gateway:DOES-NOT-EXIST"))
                .thenReturn(null);

        CanonicalTransactionRecord result =
                repository.getGatewayTransaction(
                        "DOES-NOT-EXIST"
                );

        assertNull(result);
    }

    @Test
    void shouldReturnNullForNullEventId() {

        assertNull(
                repository.getGatewayTransaction(null)
        );

        assertNull(
                repository.getBankTransaction(null)
        );

        verifyNoInteractions(valueOperations);
    }

    @Test
    void shouldIgnoreNullTransaction() {

        repository.saveGatewayTransaction(null);

        repository.saveBankTransaction(null);

        verifyNoInteractions(valueOperations);
    }

    @Test
    void shouldIgnoreTransactionWithoutEventId() {

        CanonicalTransactionRecord transaction =
                transaction(
                        null,
                        "TXN-001",
                        "BANK",
                        "REF-001",
                        "100.00"
                );

        repository.saveGatewayTransaction(transaction);

        repository.saveBankTransaction(transaction);

        verifyNoInteractions(valueOperations);
    }

    @Test
    void shouldCheckGatewayTransactionExistence() {

        when(redisTemplate.hasKey(
                "recon:gateway:REDIS-G-002"))
                .thenReturn(false);

        assertFalse(
                repository.gatewayTransactionExists(
                        "REDIS-G-002"
                )
        );

        when(redisTemplate.hasKey(
                "recon:gateway:REDIS-G-002"))
                .thenReturn(true);

        assertTrue(
                repository.gatewayTransactionExists(
                        "REDIS-G-002"
                )
        );
    }

    @Test
    void shouldCheckBankTransactionExistence() {

        when(redisTemplate.hasKey(
                "recon:bank:REDIS-B-002"))
                .thenReturn(false);

        assertFalse(
                repository.bankTransactionExists(
                        "REDIS-B-002"
                )
        );

        when(redisTemplate.hasKey(
                "recon:bank:REDIS-B-002"))
                .thenReturn(true);

        assertTrue(
                repository.bankTransactionExists(
                        "REDIS-B-002"
                )
        );
    }

    @Test
    void shouldReturnFalseForNullExistenceCheck() {

        assertFalse(
                repository.gatewayTransactionExists(null)
        );

        assertFalse(
                repository.bankTransactionExists(null)
        );

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void shouldReturnAllGatewayTransactions() {

        CanonicalTransactionRecord first =
                transaction(
                        "REDIS-G-003",
                        "TXN-REDIS-005",
                        "PAYMENT_GATEWAY",
                        "REF-REDIS-005",
                        "100.00"
                );

        CanonicalTransactionRecord second =
                transaction(
                        "REDIS-G-004",
                        "TXN-REDIS-006",
                        "PAYMENT_GATEWAY",
                        "REF-REDIS-006",
                        "200.00"
                );

        when(redisTemplate.keys(
                "recon:gateway:*"))
                .thenReturn(
                        Set.of(
                                "recon:gateway:REDIS-G-003",
                                "recon:gateway:REDIS-G-004"
                        )
                );

        when(valueOperations.get(
                "recon:gateway:REDIS-G-003"))
                .thenReturn(json(first));

        when(valueOperations.get(
                "recon:gateway:REDIS-G-004"))
                .thenReturn(json(second));

        List<CanonicalTransactionRecord> transactions =
                repository.getAllGatewayTransactions();

        assertEquals(2, transactions.size());

        assertTrue(
                transactions.stream()
                        .anyMatch(t ->
                                "REDIS-G-003"
                                        .equals(t.getEventId()))
        );

        assertTrue(
                transactions.stream()
                        .anyMatch(t ->
                                "REDIS-G-004"
                                        .equals(t.getEventId()))
        );
    }

    @Test
    void shouldReturnAllBankTransactions() {

        CanonicalTransactionRecord first =
                transaction(
                        "REDIS-B-003",
                        "TXN-REDIS-007",
                        "BANK",
                        "REF-REDIS-007",
                        "300.00"
                );

        CanonicalTransactionRecord second =
                transaction(
                        "REDIS-B-004",
                        "TXN-REDIS-008",
                        "BANK",
                        "REF-REDIS-008",
                        "400.00"
                );

        when(redisTemplate.keys(
                "recon:bank:*"))
                .thenReturn(
                        Set.of(
                                "recon:bank:REDIS-B-003",
                                "recon:bank:REDIS-B-004"
                        )
                );

        when(valueOperations.get(
                "recon:bank:REDIS-B-003"))
                .thenReturn(json(first));

        when(valueOperations.get(
                "recon:bank:REDIS-B-004"))
                .thenReturn(json(second));

        List<CanonicalTransactionRecord> transactions =
                repository.getAllBankTransactions();

        assertEquals(2, transactions.size());

        assertTrue(
                transactions.stream()
                        .anyMatch(t ->
                                "REDIS-B-003"
                                        .equals(t.getEventId()))
        );

        assertTrue(
                transactions.stream()
                        .anyMatch(t ->
                                "REDIS-B-004"
                                        .equals(t.getEventId()))
        );
    }

    @Test
    void shouldReturnEmptyListWhenNoKeysExist() {

        when(redisTemplate.keys(
                "recon:gateway:*"))
                .thenReturn(Set.of());

        List<CanonicalTransactionRecord> transactions =
                repository.getAllGatewayTransactions();

        assertNotNull(transactions);

        assertTrue(transactions.isEmpty());
    }

    @Test
    void shouldDeleteGatewayTransaction() {

        repository.deleteGatewayTransaction(
                "REDIS-G-005"
        );

        verify(redisTemplate).delete(
                "recon:gateway:REDIS-G-005"
        );
    }

    @Test
    void shouldDeleteBankTransaction() {

        repository.deleteBankTransaction(
                "REDIS-B-005"
        );

        verify(redisTemplate).delete(
                "recon:bank:REDIS-B-005"
        );
    }

    @Test
    void shouldNotDeleteWhenEventIdIsNull() {

        repository.deleteGatewayTransaction(null);

        repository.deleteBankTransaction(null);

        verify(redisTemplate, never()).delete(anyString());
    }

    private CanonicalTransactionRecord transaction(
            String eventId,
            String transactionId,
            String source,
            String referenceId,
            String amount) {

        CanonicalTransactionRecord transaction =
                new CanonicalTransactionRecord();

        transaction.setEventId(eventId);

        transaction.setTransactionId(transactionId);

        transaction.setSource(source);

        transaction.setReferenceId(referenceId);

        transaction.setReferenceKey(referenceId);

        transaction.setAmount(
                new BigDecimal(amount)
        );

        transaction.setCurrency("INR");

        transaction.setEventTimestamp(
                Instant.parse(
                        "2026-08-25T10:00:00Z"
                )
        );

        return transaction;
    }

    private String json(
            CanonicalTransactionRecord transaction) {

        try {

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();

            mapper.registerModule(
                    new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()
            );

            return mapper.writeValueAsString(transaction);

        } catch (Exception e) {

            throw new RuntimeException(e);
        }
    }

    private String capturedGatewayJson(
            CanonicalTransactionRecord transaction) {

        return json(transaction);
    }

    private String capturedBankJson(
            CanonicalTransactionRecord transaction) {

        return json(transaction);
    }
}