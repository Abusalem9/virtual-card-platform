package com.nium.virtualcard.transaction.service;

import com.nium.virtualcard.card.entity.Card;
import com.nium.virtualcard.card.enums.DeclineReason;
import com.nium.virtualcard.card.exception.CardNotFoundException;
import com.nium.virtualcard.card.repository.CardRepository;
import com.nium.virtualcard.common.exception.InvalidRequestException;
import com.nium.virtualcard.common.util.Amounts;
import com.nium.virtualcard.transaction.dto.FinancialOperationResult;
import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;
import com.nium.virtualcard.transaction.event.FinancialTransactionEvent;
import com.nium.virtualcard.transaction.exception.IdempotencyConflictException;
import com.nium.virtualcard.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Executes money movements. Each call is one database transaction: reserve the idempotency key, apply the
 * balance change with a guarded atomic UPDATE, then record the outcome. Correctness relies on the database
 * (unique key + conditional UPDATE), never on JVM locks, so any number of instances can run side by side.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TransactionService {
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 150;

    private final CardRepository cardRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    private static String validKey(String rawKey) {
        String key = rawKey.trim();
        if (key.isEmpty() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidRequestException(
                    "Idempotency-Key must contain 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return key;
    }

    @Transactional
    public FinancialOperationResult spend(Long cardId, String idempotencyKey, BigDecimal amount) {
        return execute(cardId, idempotencyKey, amount, TransactionType.SPEND);
    }

    @Transactional
    public FinancialOperationResult topUp(Long cardId, String idempotencyKey, BigDecimal amount) {
        return execute(cardId, idempotencyKey, amount, TransactionType.TOP_UP);
    }

    private FinancialOperationResult execute(Long cardId, String rawKey, BigDecimal rawAmount, TransactionType type) {
        long startedNanos = System.nanoTime();
        BigDecimal amount = Amounts.normalize(rawAmount);
        String key = validKey(rawKey);

        if (!cardRepository.existsById(cardId)) {
            throw new CardNotFoundException(cardId);
        }

        Instant now = clock.instant();
        UUID transactionId = UUID.randomUUID();

        if (transactionRepository.reserve(transactionId, cardId, type.name(), amount, key, now) == 0) {
            return replay(cardId, key, type, amount);
        }

        return applyToBalance(cardId, type, amount, now)
                ? succeed(transactionId, cardId, type, amount, startedNanos)
                : decline(transactionId, cardId, type, amount, now, startedNanos);
    }

    /**
     * The key was already reserved: return the original outcome, or fail if this is a different request.
     */
    private FinancialOperationResult replay(Long cardId, String key, TransactionType type, BigDecimal amount) {
        CardTransaction existing = transactionRepository.findByCardIdAndIdempotencyKey(cardId, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency reservation disappeared"));

        if (existing.getType() != type || existing.getAmount().compareTo(amount) != 0) {
            throw new IdempotencyConflictException();
        }

        metrics.recordReplay(type);
        log.info("Idempotent replay cardId={} transactionId={} type={}", cardId, existing.getId(), type);
        return new FinancialOperationResult(existing, true);
    }

    /**
     * The guarded UPDATE is the single place where business rules are enforced atomically.
     */
    private boolean applyToBalance(Long cardId, TransactionType type, BigDecimal amount, Instant now) {
        int changed = type == TransactionType.SPEND
                ? cardRepository.deductBalance(cardId, amount, now)
                : cardRepository.addBalance(cardId, amount, now);
        return changed > 0;
    }

    private FinancialOperationResult succeed(UUID transactionId, Long cardId, TransactionType type,
                                             BigDecimal amount, long startedNanos) {
        Card card = loadCard(cardId);
        CardTransaction completed = complete(transactionId, TransactionStatus.SUCCESSFUL, card.getBalance(), null);
        metrics.record(type, "successful", System.nanoTime() - startedNanos);
        publishAudit(completed);
        log.info("Transaction completed cardId={} transactionId={} type={} amount={} balance={}",
                cardId, transactionId, type, amount, card.getBalance());
        return new FinancialOperationResult(completed, false);
    }

    private FinancialOperationResult decline(UUID transactionId, Long cardId, TransactionType type,
                                             BigDecimal amount, Instant now, long startedNanos) {
        Card card = loadCard(cardId);
        DeclineReason reason = declineReasonFor(card, type, amount, now);
        CardTransaction declined = complete(transactionId, TransactionStatus.DECLINED, card.getBalance(), reason.name());
        metrics.record(type, "declined", System.nanoTime() - startedNanos);
        publishAudit(declined);
        log.info("Transaction declined cardId={} transactionId={} type={} amount={} reason={}",
                cardId, transactionId, type, amount, reason);
        return new FinancialOperationResult(declined, false);
    }

    private DeclineReason declineReasonFor(Card card, TransactionType type, BigDecimal amount, Instant now) {
        DeclineReason reason = type == TransactionType.SPEND
                ? card.spendDeclineReason(amount, now)
                : card.topUpDeclineReason(now);
        // The guarded UPDATE matched nothing but the reloaded card looks fine: state changed in between.
        return reason != null ? reason : DeclineReason.CARD_NOT_ACTIVE;
    }

    private Card loadCard(Long cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new CardNotFoundException(cardId));
    }

    private CardTransaction complete(UUID transactionId, TransactionStatus status, BigDecimal balanceAfter, String failureCode) {
        transactionRepository.complete(transactionId, status.name(), balanceAfter, failureCode, clock.instant());
        return transactionRepository.findById(transactionId).orElseThrow();
    }

    private void publishAudit(CardTransaction transaction) {
        eventPublisher.publishEvent(new FinancialTransactionEvent(
                transaction.getCardId(),
                transaction.getId(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getFailureCode(),
                transaction.getCompletedAt() == null ? clock.instant() : transaction.getCompletedAt()
        ));
    }
}
