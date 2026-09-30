package com.nium.virtualcard.card.event;

import com.nium.virtualcard.card.enums.CardStatus;

import java.time.Instant;

public record CardStatusChangedEvent(Long cardId, CardStatus from, CardStatus to, Instant occurredAt) {
}
