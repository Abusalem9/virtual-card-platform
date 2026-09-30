package com.nium.virtualcard.card.event;

import java.math.BigDecimal;
import java.time.Instant;

public record CardIssuedEvent(Long cardId, BigDecimal initialBalance, Instant expiresAt, Instant occurredAt) {
}
