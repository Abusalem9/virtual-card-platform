package com.nium.virtualcard.card.entity;

import com.nium.virtualcard.card.enums.CardStatus;
import com.nium.virtualcard.card.enums.DeclineReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Getters only, deliberately no setters: balance and status change exclusively through guarded SQL in
 * CardRepository, so a full-entity save can never overwrite a concurrent money movement.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name = "cards")
public class Card {
    @Id
    private Long id;

    @Column(name = "cardholder_name", nullable = false, length = 150)
    private String cardholderName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * Why a top-up would be refused for this card, or null when it is allowed. The same rules are
     * enforced atomically in SQL (see CardRepository); this explains a refusal after the fact.
     */
    public DeclineReason topUpDeclineReason(Instant now) {
        if (isExpiredAt(now)) {
            return DeclineReason.CARD_EXPIRED;
        }
        if (status != CardStatus.ACTIVE) {
            return DeclineReason.CARD_NOT_ACTIVE;
        }
        return null;
    }

    public DeclineReason spendDeclineReason(BigDecimal amount, Instant now) {
        DeclineReason reason = topUpDeclineReason(now);
        if (reason != null) {
            return reason;
        }
        return balance.compareTo(amount) < 0 ? DeclineReason.INSUFFICIENT_FUNDS : null;
    }
}
