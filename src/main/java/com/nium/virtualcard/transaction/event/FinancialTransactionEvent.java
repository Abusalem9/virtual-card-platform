package com.nium.virtualcard.transaction.event;

import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record FinancialTransactionEvent(
        Long cardId,
        UUID transactionId,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        String failureCode,
        Instant occurredAt
) {
}
