package com.nium.virtualcard.audit.listener;

import com.nium.virtualcard.audit.entity.AuditEvent;
import com.nium.virtualcard.audit.repository.AuditEventRepository;
import com.nium.virtualcard.card.event.CardIssuedEvent;
import com.nium.virtualcard.card.event.CardStatusChangedEvent;
import com.nium.virtualcard.transaction.event.FinancialTransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists audit records after the business transaction commits, off the request thread. The audit module
 * depends on the modules it observes; they know nothing about it. Delivery is in-process and best effort
 * (see README: a transactional outbox is the production answer).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class AsyncAuditListener {
    private final AuditEventRepository auditEventRepository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransaction(FinancialTransactionEvent event) {
        String details = "type=%s,status=%s,amount=%s%s".formatted(
                event.type(),
                event.status(),
                event.amount(),
                event.failureCode() == null ? "" : ",failureCode=" + event.failureCode()
        );
        record("FINANCIAL_TRANSACTION_COMPLETED", event.cardId(), event.transactionId(), details, event.occurredAt());
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCardIssued(CardIssuedEvent event) {
        String details = "initialBalance=%s,expiresAt=%s".formatted(event.initialBalance(), event.expiresAt());
        record("CARD_ISSUED", event.cardId(), null, details, event.occurredAt());
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCardStatusChanged(CardStatusChangedEvent event) {
        record("CARD_STATUS_CHANGED", event.cardId(), null,
                "from=%s,to=%s".formatted(event.from(), event.to()), event.occurredAt());
    }

    private void record(String eventType, Long cardId, UUID transactionId, String details, Instant occurredAt) {
        auditEventRepository.save(new AuditEvent(UUID.randomUUID(), eventType, cardId, transactionId, details, occurredAt));
        log.info("Async audit persisted eventType={} cardId={} transactionId={}", eventType, cardId, transactionId);
    }
}
