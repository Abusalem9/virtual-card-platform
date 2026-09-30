package com.nium.virtualcard.card.scheduler;

import com.nium.virtualcard.card.repository.CardRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Slf4j
@Component
public class CardExpirationScheduler {
    private final CardRepository cardRepository;
    private final Clock clock;
    private final Counter expiredCounter;

    public CardExpirationScheduler(CardRepository cardRepository, Clock clock, MeterRegistry meterRegistry) {
        this.cardRepository = cardRepository;
        this.clock = clock;
        this.expiredCounter = Counter.builder("virtual_card_expired_total")
                .description("Number of cards closed by the expiration scheduler")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.card-expiration.scan-interval-ms:60000}")
    @Transactional
    public void expireCards() {
        int expired = cardRepository.closeExpiredCards(clock.instant());
        if (expired > 0) {
            expiredCounter.increment(expired);
            log.info("Closed {} expired virtual cards", expired);
        }
    }
}
