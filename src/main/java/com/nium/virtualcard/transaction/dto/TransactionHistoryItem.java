package com.nium.virtualcard.transaction.dto;

import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionHistoryItem(
        UUID id,
        TransactionType type,
        BigDecimal amount,
        TransactionStatus status,
        BigDecimal balanceAfter,
        String failureCode,
        Instant createdAt
) {
    public static TransactionHistoryItem from(CardTransaction tx) {
        return new TransactionHistoryItem(
                tx.getId(), tx.getType(), tx.getAmount(), tx.getStatus(),
                tx.getBalanceAfter(), tx.getFailureCode(), tx.getCreatedAt()
        );
    }
}
