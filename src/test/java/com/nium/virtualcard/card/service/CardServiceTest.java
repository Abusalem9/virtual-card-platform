package com.nium.virtualcard.card.service;

import com.nium.virtualcard.card.dto.CreateCardCommand;
import com.nium.virtualcard.card.entity.Card;
import com.nium.virtualcard.card.enums.CardStatus;
import com.nium.virtualcard.card.event.CardIssuedEvent;
import com.nium.virtualcard.card.event.CardStatusChangedEvent;
import com.nium.virtualcard.card.exception.CardNotFoundException;
import com.nium.virtualcard.card.exception.InvalidStatusTransitionException;
import com.nium.virtualcard.card.repository.CardRepository;
import com.nium.virtualcard.common.exception.InvalidRequestException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");

    @Mock
    CardRepository cardRepository;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    CardNumberGenerator cardNumberGenerator;

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    CardService service;

    @BeforeEach
    void setUp() {
        service = new CardService(cardRepository, eventPublisher, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC), cardNumberGenerator);
        lenient().when(cardNumberGenerator.next()).thenReturn(4539148803436467L);
    }

    @Test
    void createsActiveCardWithInitialBalance() {
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        Card card = service.create(new CreateCardCommand(" Abusalem M ", new BigDecimal("100.00"), null));

        assertEquals("Abusalem M", card.getCardholderName());
        assertEquals(new BigDecimal("100.00"), card.getBalance());
        assertEquals(CardStatus.ACTIVE, card.getStatus());
        assertEquals(NOW, card.getCreatedAt());
        assertTrue(card.getExpiresAt().isAfter(NOW));
        assertNotNull(card.getId());
        assertEquals(1.0, meterRegistry.counter("virtual_card_cards_created_total").count());
    }

    @Test
    void usesGeneratedCardNumberAsId() {
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        Card card = service.create(new CreateCardCommand("Numeric", new BigDecimal("1.00"), null));

        assertEquals(4539148803436467L, card.getId());
    }

    @Test
    void regeneratesCardNumberOnCollision() {
        when(cardNumberGenerator.next()).thenReturn(4111111111111111L, 4539148803436467L);
        when(cardRepository.existsById(4111111111111111L)).thenReturn(true);
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        Card card = service.create(new CreateCardCommand("Clash", new BigDecimal("1.00"), null));

        assertEquals(4539148803436467L, card.getId());
    }

    @Test
    void givesUpWhenEveryGeneratedNumberIsTaken() {
        when(cardRepository.existsById(anyLong())).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.create(new CreateCardCommand("Clash", new BigDecimal("1.00"), null)));
        verify(cardRepository, never()).save(any(Card.class));
    }

    @Test
    void publishesCardIssuedEventForOtherModules() {
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        Card card = service.create(new CreateCardCommand("Audit", new BigDecimal("5.00"), null));

        ArgumentCaptor<CardIssuedEvent> captor = ArgumentCaptor.forClass(CardIssuedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(card.getId(), captor.getValue().cardId());
        assertEquals(new BigDecimal("5.00"), captor.getValue().initialBalance());
        assertEquals(NOW, captor.getValue().occurredAt());
    }

    @Test
    void defaultExpiryIsThreeYearsOut() {
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        Card card = service.create(new CreateCardCommand("Default", BigDecimal.ZERO, null));

        assertEquals(Instant.parse("2029-09-29T05:00:00Z"), card.getExpiresAt());
    }

    @Test
    void rejectsExpiryInThePast() {
        var command = new CreateCardCommand("Past", BigDecimal.TEN.setScale(2), NOW.minusSeconds(1));

        assertThrows(InvalidRequestException.class, () -> service.create(command));
        verifyNoInteractions(cardRepository, eventPublisher);
    }

    @Test
    void rejectsAmountsWithMoreThanTwoDecimals() {
        var command = new CreateCardCommand("Precise", new BigDecimal("10.001"), null);

        assertThrows(InvalidRequestException.class, () -> service.create(command));
    }

    @Test
    void getThrowsForUnknownCard() {
        Long id = 4539148803436467L;
        when(cardRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(CardNotFoundException.class, () -> service.get(id));
    }

    @Test
    void blocksActiveCardAndPublishesEvent() {
        Card card = card(CardStatus.ACTIVE);
        Card blocked = new Card(card.getId(), "Holder", card.getBalance(), CardStatus.BLOCKED, NOW, card.getExpiresAt());
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card), Optional.of(blocked));
        when(cardRepository.updateStatus(card.getId(), "ACTIVE", "BLOCKED")).thenReturn(1);

        Card result = service.updateStatus(card.getId(), CardStatus.BLOCKED);

        assertEquals(CardStatus.BLOCKED, result.getStatus());
        ArgumentCaptor<CardStatusChangedEvent> captor = ArgumentCaptor.forClass(CardStatusChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(CardStatus.ACTIVE, captor.getValue().from());
        assertEquals(CardStatus.BLOCKED, captor.getValue().to());
    }

    @Test
    void closedCardCannotBeReopened() {
        Card card = card(CardStatus.CLOSED);
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        assertThrows(InvalidStatusTransitionException.class, () -> service.updateStatus(card.getId(), CardStatus.ACTIVE));
        verify(cardRepository, never()).updateStatus(any(), anyString(), anyString());
    }

    @Test
    void sameStatusIsRejected() {
        Card card = card(CardStatus.ACTIVE);
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card));

        assertThrows(InvalidStatusTransitionException.class, () -> service.updateStatus(card.getId(), CardStatus.ACTIVE));
    }

    @Test
    void racingStatusChangeIsReportedAsConflict() {
        Card card = card(CardStatus.ACTIVE);
        Card closed = new Card(card.getId(), "Holder", card.getBalance(), CardStatus.CLOSED, NOW, card.getExpiresAt());
        when(cardRepository.findById(card.getId())).thenReturn(Optional.of(card), Optional.of(closed));
        when(cardRepository.updateStatus(card.getId(), "ACTIVE", "BLOCKED")).thenReturn(0);

        assertThrows(InvalidStatusTransitionException.class, () -> service.updateStatus(card.getId(), CardStatus.BLOCKED));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void updateStatusThrowsForUnknownCard() {
        Long id = 4539148803436467L;
        when(cardRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(CardNotFoundException.class, () -> service.updateStatus(id, CardStatus.BLOCKED));
    }

    private Card card(CardStatus status) {
        return new Card(4539148803436467L, "Holder", new BigDecimal("10.00"), status, NOW, NOW.plusSeconds(3600));
    }
}
