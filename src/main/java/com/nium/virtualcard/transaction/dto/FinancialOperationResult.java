package com.nium.virtualcard.transaction.dto;

import com.nium.virtualcard.card.enums.DeclineReason;
import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.enums.TransactionStatus;

public record FinancialOperationResult(CardTransaction transaction, boolean replayed) {

    public boolean successful() {
        return transaction.getStatus() != TransactionStatus.DECLINED;
    }

    public DeclineReason declineReason() {
        return successful() ? null : DeclineReason.valueOf(transaction.getFailureCode());
    }

    public String failureCode() {
        DeclineReason reason = declineReason();
        return reason == null ? null : reason.name();
    }

    public String failureMessage() {
        DeclineReason reason = declineReason();
        return reason == null ? null : reason.message();
    }
}
