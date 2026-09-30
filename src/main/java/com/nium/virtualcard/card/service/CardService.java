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
import com.nium.virtualcard.common.util.Amounts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@Slf4j
@Service
public class CardService {
    private static final int DEFAULT_VALIDITY_YEARS = 3;
    private static final int MAX_ID_ATTEMPTS = 5;

    private final CardRepository cardRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final CardNumberGenerator cardNumberGenerator;
    private final Counter createdCounter;

    public CardService(CardRepository cardRepository, ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry, Clock clock,
                       CardNumberGenerator cardNumberGenerator) {
        this.cardRepository = cardRepository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.cardNumberGenerator = cardNumberGenerator;
        this.createdCounter = Counter.builder("virtual_card_cards_created_total").description("Number of virtual cards issued").register(meterRegistry);
    }

    @Transactional
    public Card create(CreateCardCommand command) {
        var balance = Amounts.normalize(command.initialBalance());
        Instant now = clock.instant();
        Instant expiresAt = command.expiresAt() != null ? command.expiresAt() : now.atZone(ZoneOffset.UTC).plusYears(DEFAULT_VALIDITY_YEARS).toInstant();

        if (!expiresAt.isAfter(now)) {
            throw new InvalidRequestException("expiresAt must be in the future");
        }

        var card = cardRepository.save(new Card(nextCardId(), command.cardholderName().trim(), balance, CardStatus.ACTIVE, now, expiresAt));

        // Other modules (ledger history, audit) react to this event; the card module does not know them.
        eventPublisher.publishEvent(new CardIssuedEvent(card.getId(), balance, expiresAt, now));
        createdCounter.increment();
        log.info("Card issued cardId={} initialBalance={} expiresAt={}", card.getId(), balance, expiresAt);
        return card;
    }

    // save() on an assigned id is a merge and would overwrite an existing card, so check for a clash first.
    // The primary key still guards the small window between the check and the insert.
    private long nextCardId() {
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            long candidate = cardNumberGenerator.next();
            if (!cardRepository.existsById(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique card number after " + MAX_ID_ATTEMPTS + " attempts");
    }

    @Transactional(readOnly = true)
    public Card get(Long cardId) {
        return cardRepository.findById(cardId).orElseThrow(() -> new CardNotFoundException(cardId));
    }

    @Transactional
    public Card updateStatus(Long cardId, CardStatus target) {
        var card = get(cardId);
        CardStatus current = card.getStatus();

        if (!current.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(current, target);
        }

        // Compare-and-set on status; a racing change (e.g. expiry closing the card) makes this a no-op.
        if (cardRepository.updateStatus(cardId, current.name(), target.name()) == 0) {
            throw new InvalidStatusTransitionException(get(cardId).getStatus(), target);
        }

        eventPublisher.publishEvent(new CardStatusChangedEvent(cardId, current, target, clock.instant()));
        meterRegistry.counter("virtual_card_status_changes_total", "to", target.name().toLowerCase()).increment();
        log.info("Card status changed cardId={} from={} to={}", cardId, current, target);
        return get(cardId);
    }
}
