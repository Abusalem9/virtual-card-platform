package com.nium.virtualcard.card.exception;

import com.nium.virtualcard.common.exception.ApiException;
import com.nium.virtualcard.common.exception.ErrorCode;

public class CardNotFoundException extends ApiException {
    public CardNotFoundException(Long cardId) {
        super(ErrorCode.CARD_NOT_FOUND, "Card not found: " + cardId);
    }
}
