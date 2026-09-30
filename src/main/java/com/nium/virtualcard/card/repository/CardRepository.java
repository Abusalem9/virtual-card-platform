package com.nium.virtualcard.card.repository;

import com.nium.virtualcard.card.entity.Card;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

public interface CardRepository extends JpaRepository<Card, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE cards
               SET balance = balance - :amount
             WHERE id = :cardId
               AND status = 'ACTIVE'
               AND expires_at > :now
               AND balance >= :amount
            """, nativeQuery = true)
    int deductBalance(@Param("cardId") Long cardId, @Param("amount") BigDecimal amount, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE cards
               SET balance = balance + :amount
             WHERE id = :cardId
               AND status = 'ACTIVE'
               AND expires_at > :now
            """, nativeQuery = true)
    int addBalance(@Param("cardId") Long cardId, @Param("amount") BigDecimal amount, @Param("now") Instant now);

    /**
     * Status-only update so a concurrent balance change is never overwritten by a full-entity save.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE cards
               SET status = :newStatus
             WHERE id = :cardId
               AND status = :currentStatus
            """, nativeQuery = true)
    int updateStatus(@Param("cardId") Long cardId, @Param("currentStatus") String currentStatus, @Param("newStatus") String newStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE cards
               SET status = 'CLOSED'
             WHERE status <> 'CLOSED'
               AND expires_at <= :now
            """, nativeQuery = true)
    int closeExpiredCards(@Param("now") Instant now);
}
