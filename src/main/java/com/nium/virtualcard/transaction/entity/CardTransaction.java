package com.nium.virtualcard.transaction.entity;

import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Getters only: rows are created by the guarded reserve/complete SQL in TransactionRepository (or by the
 * issuance factory below) and are never edited through the entity.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "card_transactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_card_idempotency", columnNames = {"card_id", "idempotency_key"}))
public class CardTransaction {
    /**
     * Reserved key for the system-generated issuance entry (at most one per card).
     */
    static final String ISSUANCE_KEY = "system:issuance";

    @Id
    private UUID id;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "balance_after", precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public static CardTransaction issuance(UUID id, Long cardId, BigDecimal amount, Instant now) {
        CardTransaction tx = new CardTransaction();
        tx.id = id;
        tx.cardId = cardId;
        tx.type = TransactionType.ISSUANCE;
        tx.amount = amount;
        tx.status = TransactionStatus.SUCCESSFUL;
        tx.idempotencyKey = ISSUANCE_KEY;
        tx.balanceAfter = amount;
        tx.createdAt = now;
        tx.completedAt = now;
        return tx;
    }
}
