package com.nium.virtualcard.transaction.dto;

import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        Long cardId,
        TransactionType type,
        BigDecimal amount,
        TransactionStatus status,
        BigDecimal balance,
        Instant createdAt,
        Instant completedAt
) {
    public static TransactionResponse from(CardTransaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getCardId(),
                tx.getType(),
                tx.getAmount(),
                tx.getStatus(),
                tx.getBalanceAfter(),
                tx.getCreatedAt(),
                tx.getCompletedAt()
        );
    }
}
