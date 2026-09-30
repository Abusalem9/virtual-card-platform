package com.nium.virtualcard.card.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateCardRequest(
        @NotBlank @Size(max = 150) String cardholderName,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal initialBalance,
        Instant expiresAt
) {
    public CreateCardRequest(String cardholderName, BigDecimal initialBalance) {
        this(cardholderName, initialBalance, null);
    }
}
