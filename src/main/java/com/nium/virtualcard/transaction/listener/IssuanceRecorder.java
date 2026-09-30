package com.nium.virtualcard.transaction.listener;

import com.nium.virtualcard.card.event.CardIssuedEvent;
import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Records a card's opening balance as its first history entry so history always reconciles with balance.
 * A plain (synchronous) listener, so it runs inside the issuing transaction: card and entry commit together.
 */
@RequiredArgsConstructor
@Component
public class IssuanceRecorder {
    private final TransactionRepository transactionRepository;

    @EventListener
    public void on(CardIssuedEvent event) {
        if (event.initialBalance().signum() > 0) {
            transactionRepository.save(CardTransaction.issuance(
                    UUID.randomUUID(), event.cardId(), event.initialBalance(), event.occurredAt()));
        }
    }
}
