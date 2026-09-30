package com.nium.virtualcard.card.enums;

public enum CardStatus {
    ACTIVE,
    BLOCKED,
    CLOSED;

    public boolean canTransitionTo(CardStatus target) {
        return this != CLOSED && this != target;
    }
}
