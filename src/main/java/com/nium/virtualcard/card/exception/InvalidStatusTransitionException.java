package com.nium.virtualcard.card.exception;

import com.nium.virtualcard.card.enums.CardStatus;
import com.nium.virtualcard.common.exception.ApiException;
import com.nium.virtualcard.common.exception.ErrorCode;

public class InvalidStatusTransitionException extends ApiException {
    public InvalidStatusTransitionException(CardStatus from, CardStatus to) {
        super(ErrorCode.INVALID_STATUS_TRANSITION, "Card cannot move from " + from + " to " + to);
    }
}
