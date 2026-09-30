package com.nium.virtualcard.card.dto;

import com.nium.virtualcard.card.entity.Card;
import com.nium.virtualcard.card.enums.CardStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record CardResponse(
        Long id,
        String cardholderName,
        BigDecimal balance,
        CardStatus status,
        Instant createdAt,
        Instant expiresAt
) {
    public static CardResponse from(Card card) {
        return new CardResponse(
                card.getId(),
                card.getCardholderName(),
                card.getBalance(),
                card.getStatus(),
                card.getCreatedAt(),
                card.getExpiresAt()
        );
    }
}
