package com.nium.virtualcard.card.dto;

import com.nium.virtualcard.card.enums.CardStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateCardStatusRequest(@NotNull CardStatus status) {
}
