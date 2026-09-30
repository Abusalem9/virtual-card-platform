package com.nium.virtualcard.card.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateCardCommand(String cardholderName, BigDecimal initialBalance, Instant expiresAt) {
}
