package com.nium.virtualcard.transaction.service;

import com.nium.virtualcard.card.entity.Card;
import com.nium.virtualcard.card.enums.CardStatus;
import com.nium.virtualcard.card.exception.CardNotFoundException;
import com.nium.virtualcard.card.repository.CardRepository;
import com.nium.virtualcard.common.exception.InvalidRequestException;
import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;
import com.nium.virtualcard.transaction.event.FinancialTransactionEvent;
import com.nium.virtualcard.transaction.exception.IdempotencyConflictException;
import com.nium.virtualcard.transaction.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.isNull;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");
    private static final BigDecimal TEN = new BigDecimal("10.00");

    @Mock
    CardRepository cardRepository;
    @Mock
    TransactionRepository transactionRepository;
    @Mock
    ApplicationEventPublisher eventPublisher;

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    TransactionService service;
    Long cardId = 4539148803436467L;

    @BeforeEach
    void setUp() {
        service = new TransactionService(cardRepository, transactionRepository, new TransactionMetrics(meterRegistry),
                eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void spendSucceedsWhenGuardedUpdateMatches() {
        when(cardRepository.existsById(cardId)).thenReturn(true);
        when(transactionRepository.reserve(any(), eq(cardId), eq("SPEND"), eq(TEN), eq("k1"), eq(NOW))).thenReturn(1);
        when(cardRepository.deductBalance(cardId, TEN, NOW)).thenReturn(1);
        when(cardRepository.findById(cardId)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "90.00", NOW.plusSeconds(60))));
        when(transactionRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.of(tx(TransactionType.SPEND, TransactionStatus.SUCCESSFUL, TEN, null)));

        var result = service.spend(cardId, "k1", new BigDecimal("10"));

        assertTrue(result.successful());
        assertFalse(result.replayed());
        verify(transactionRepository).complete(any(), eq("SUCCESSFUL"), eq(new BigDecimal("90.00")), isNull(), any());
        verify(eventPublisher).publishEvent(any(FinancialTransactionEvent.class));
        assertEquals(1.0, meterRegistry.counter("virtual_card_transactions_total", "type", "spend", "status", "successful").count());
    }

    @Test
    void spendIsDeclinedForInsufficientFunds() {
        stubDeclinedSpend(card(CardStatus.ACTIVE, "5.00", NOW.plusSeconds(60)), "INSUFFICIENT_FUNDS");

        var result = service.spend(cardId, "k1", TEN);

        assertFalse(result.successful());
        assertEquals("INSUFFICIENT_FUNDS", result.failureCode());
        verify(transactionRepository).complete(any(), eq("DECLINED"), eq(new BigDecimal("5.00")), eq("INSUFFICIENT_FUNDS"), any());
    }

    @Test
    void spendIsDeclinedForBlockedCard() {
        stubDeclinedSpend(card(CardStatus.BLOCKED, "100.00", NOW.plusSeconds(60)), "CARD_NOT_ACTIVE");

        assertEquals("CARD_NOT_ACTIVE", service.spend(cardId, "k1", TEN).failureCode());
    }

    @Test
    void spendIsDeclinedForClosedCard() {
        stubDeclinedSpend(card(CardStatus.CLOSED, "100.00", NOW.plusSeconds(60)), "CARD_NOT_ACTIVE");

        assertEquals("CARD_NOT_ACTIVE", service.spend(cardId, "k1", TEN).failureCode());
    }

    @Test
    void spendIsDeclinedForExpiredCard() {
        stubDeclinedSpend(card(CardStatus.ACTIVE, "100.00", NOW), "CARD_EXPIRED");

        assertEquals("CARD_EXPIRED", service.spend(cardId, "k1", TEN).failureCode());
    }

    @Test
    void topUpIsDeclinedForBlockedCard() {
        when(cardRepository.existsById(cardId)).thenReturn(true);
        when(transactionRepository.reserve(any(), eq(cardId), eq("TOP_UP"), eq(TEN), eq("k1"), eq(NOW))).thenReturn(1);
        when(cardRepository.addBalance(cardId, TEN, NOW)).thenReturn(0);
        when(cardRepository.findById(cardId)).thenReturn(Optional.of(card(CardStatus.BLOCKED, "0.00", NOW.plusSeconds(60))));
        when(transactionRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.of(tx(TransactionType.TOP_UP, TransactionStatus.DECLINED, TEN, "CARD_NOT_ACTIVE")));

        var result = service.topUp(cardId, "k1", TEN);

        assertEquals("CARD_NOT_ACTIVE", result.failureCode());
        verify(cardRepository, never()).deductBalance(any(), any(), any());
    }

    @Test
    void topUpSucceeds() {
        when(cardRepository.existsById(cardId)).thenReturn(true);
        when(transactionRepository.reserve(any(), eq(cardId), eq("TOP_UP"), eq(TEN), eq("k1"), eq(NOW))).thenReturn(1);
        when(cardRepository.addBalance(cardId, TEN, NOW)).thenReturn(1);
        when(cardRepository.findById(cardId)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "110.00", NOW.plusSeconds(60))));
        when(transactionRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.of(tx(TransactionType.TOP_UP, TransactionStatus.SUCCESSFUL, TEN, null)));

        assertTrue(service.topUp(cardId, "k1", TEN).successful());
        verify(cardRepository, never()).deductBalance(any(), any(), any());
    }

    @Test
    void replayWithSameRequestReturnsOriginalWithoutTouchingBalance() {
        stubReplay(tx(TransactionType.SPEND, TransactionStatus.SUCCESSFUL, TEN, null));

        var result = service.spend(cardId, "k1", TEN);

        assertTrue(result.successful());
        assertTrue(result.replayed());
        verify(cardRepository, never()).deductBalance(any(), any(), any());
        verify(cardRepository, never()).addBalance(any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        assertEquals(1.0, meterRegistry.counter("virtual_card_idempotent_replays_total", "type", "spend").count());
    }

    @Test
    void replayOfDeclinedTransactionReturnsSameFailure() {
        stubReplay(tx(TransactionType.SPEND, TransactionStatus.DECLINED, TEN, "INSUFFICIENT_FUNDS"));

        var result = service.spend(cardId, "k1", TEN);

        assertFalse(result.successful());
        assertTrue(result.replayed());
        assertEquals("INSUFFICIENT_FUNDS", result.failureCode());
        assertEquals("Card does not have sufficient balance", result.failureMessage());
    }

    @Test
    void replayWithDifferentAmountIsConflict() {
        stubReplay(tx(TransactionType.SPEND, TransactionStatus.SUCCESSFUL, new BigDecimal("20.00"), null));

        assertThrows(IdempotencyConflictException.class, () -> service.spend(cardId, "k1", TEN));
    }

    @Test
    void replayWithDifferentTypeIsConflict() {
        stubReplay(tx(TransactionType.TOP_UP, TransactionStatus.SUCCESSFUL, TEN, null));

        assertThrows(IdempotencyConflictException.class, () -> service.spend(cardId, "k1", TEN));
    }

    @Test
    void replayComparesAmountsNumerically() {
        stubReplay(tx(TransactionType.SPEND, TransactionStatus.SUCCESSFUL, new BigDecimal("10.0"), null));

        assertTrue(service.spend(cardId, "k1", new BigDecimal("10.00")).replayed());
    }

    @Test
    void unknownCardIsNotFound() {
        when(cardRepository.existsById(cardId)).thenReturn(false);

        assertThrows(CardNotFoundException.class, () -> service.spend(cardId, "k1", TEN));
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        assertThrows(InvalidRequestException.class, () -> service.spend(cardId, "   ", TEN));
        verifyNoInteractions(cardRepository, transactionRepository);
    }

    @Test
    void overlongIdempotencyKeyIsRejected() {
        assertThrows(InvalidRequestException.class, () -> service.topUp(cardId, "x".repeat(151), TEN));
    }

    @Test
    void amountWithMoreThanTwoDecimalsIsRejected() {
        assertThrows(InvalidRequestException.class, () -> service.spend(cardId, "k1", new BigDecimal("1.001")));
    }

    @Test
    void idempotencyKeyIsTrimmedBeforeReservation() {
        when(cardRepository.existsById(cardId)).thenReturn(true);
        when(transactionRepository.reserve(any(), eq(cardId), anyString(), any(), eq("k1"), any())).thenReturn(0);
        when(transactionRepository.findByCardIdAndIdempotencyKey(cardId, "k1"))
                .thenAnswer(inv -> Optional.of(tx(TransactionType.SPEND, TransactionStatus.SUCCESSFUL, TEN, null)));

        assertTrue(service.spend(cardId, "  k1  ", TEN).replayed());
    }

    private void stubDeclinedSpend(Card card, String code) {
        when(cardRepository.existsById(cardId)).thenReturn(true);
        when(transactionRepository.reserve(any(), eq(cardId), eq("SPEND"), eq(TEN), eq("k1"), eq(NOW))).thenReturn(1);
        when(cardRepository.deductBalance(cardId, TEN, NOW)).thenReturn(0);
        when(cardRepository.findById(cardId)).thenReturn(Optional.of(card));
        when(transactionRepository.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.of(tx(TransactionType.SPEND, TransactionStatus.DECLINED, TEN, code)));
    }

    private void stubReplay(CardTransaction existing) {
        when(cardRepository.existsById(cardId)).thenReturn(true);
        when(transactionRepository.reserve(any(), eq(cardId), anyString(), any(), eq("k1"), any())).thenReturn(0);
        when(transactionRepository.findByCardIdAndIdempotencyKey(cardId, "k1")).thenReturn(Optional.of(existing));
    }

    private Card card(CardStatus status, String balance, Instant expiresAt) {
        return new Card(cardId, "Holder", new BigDecimal(balance), status, NOW.minusSeconds(3600), expiresAt);
    }

    private CardTransaction tx(TransactionType type, TransactionStatus status, BigDecimal amount, String failureCode) {
        CardTransaction tx = mock(CardTransaction.class);
        lenient().when(tx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(tx.getCardId()).thenReturn(cardId);
        lenient().when(tx.getType()).thenReturn(type);
        lenient().when(tx.getStatus()).thenReturn(status);
        lenient().when(tx.getAmount()).thenReturn(amount);
        lenient().when(tx.getFailureCode()).thenReturn(failureCode);
        lenient().when(tx.getCreatedAt()).thenReturn(NOW);
        lenient().when(tx.getCompletedAt()).thenReturn(NOW);
        return tx;
    }
}
