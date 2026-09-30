package com.nium.virtualcard.transaction.listener;

import com.nium.virtualcard.card.event.CardIssuedEvent;
import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;
import com.nium.virtualcard.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class IssuanceRecorderTest {
    private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");

    @Mock
    TransactionRepository transactionRepository;
    @InjectMocks
    IssuanceRecorder recorder;

    @Test
    void recordsOpeningBalanceAsFirstHistoryEntry() {
        Long cardId = 4539148803436467L;

        recorder.on(new CardIssuedEvent(cardId, new BigDecimal("25.00"), NOW.plusSeconds(60), NOW));

        ArgumentCaptor<CardTransaction> captor = ArgumentCaptor.forClass(CardTransaction.class);
        verify(transactionRepository).save(captor.capture());
        CardTransaction saved = captor.getValue();
        assertEquals(cardId, saved.getCardId());
        assertEquals(TransactionType.ISSUANCE, saved.getType());
        assertEquals(TransactionStatus.SUCCESSFUL, saved.getStatus());
        assertEquals(new BigDecimal("25.00"), saved.getAmount());
        assertEquals(new BigDecimal("25.00"), saved.getBalanceAfter());
        assertEquals(NOW, saved.getCreatedAt());
    }

    @Test
    void zeroBalanceCardGetsNoEntry() {
        recorder.on(new CardIssuedEvent(4111111111111111L, new BigDecimal("0.00"), NOW.plusSeconds(60), NOW));

        verifyNoInteractions(transactionRepository);
    }
}
