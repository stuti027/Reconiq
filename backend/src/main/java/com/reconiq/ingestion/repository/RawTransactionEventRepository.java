package com.reconiq.ingestion.repository;

import com.reconiq.ingestion.entity.RawTransactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RawTransactionEventRepository extends JpaRepository<RawTransactionEvent, Long> {
    boolean existsByEventId(String eventId);
    Optional<RawTransactionEvent> findByEventId(String eventId);
}