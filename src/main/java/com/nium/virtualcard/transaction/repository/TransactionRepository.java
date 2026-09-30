package com.nium.virtualcard.transaction.repository;

import com.nium.virtualcard.transaction.entity.CardTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<CardTransaction, UUID> {

    Optional<CardTransaction> findByCardIdAndIdempotencyKey(Long cardId, String idempotencyKey);

    Page<CardTransaction> findByCardId(Long cardId, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO card_transactions(
                id, card_id, type, amount, status, idempotency_key, created_at
            ) VALUES (
                :id, :cardId, :type, :amount, 'PENDING', :idempotencyKey, :createdAt
            )
            ON CONFLICT (card_id, idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int reserve(
            @Param("id") UUID id,
            @Param("cardId") Long cardId,
            @Param("type") String type,
            @Param("amount") BigDecimal amount,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("createdAt") Instant createdAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE card_transactions
               SET status = :status,
                   balance_after = :balanceAfter,
                   failure_code = :failureCode,
                   completed_at = :completedAt
             WHERE id = :id
            """, nativeQuery = true)
    int complete(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("balanceAfter") BigDecimal balanceAfter,
            @Param("failureCode") String failureCode,
            @Param("completedAt") Instant completedAt
    );
}
