package com.nium.virtualcard.card.enums;

public enum DeclineReason {
    CARD_EXPIRED("Card has expired"),
    CARD_NOT_ACTIVE("Spending and top-ups are only allowed on active cards"),
    INSUFFICIENT_FUNDS("Card does not have sufficient balance");

    private final String message;

    DeclineReason(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
