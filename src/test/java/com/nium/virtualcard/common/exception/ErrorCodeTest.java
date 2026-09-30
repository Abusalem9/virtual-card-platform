package com.nium.virtualcard.common.exception;

import com.nium.virtualcard.card.enums.CardStatus;
import com.nium.virtualcard.card.enums.DeclineReason;
import com.nium.virtualcard.card.exception.CardNotFoundException;
import com.nium.virtualcard.card.exception.InvalidStatusTransitionException;
import com.nium.virtualcard.transaction.exception.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorCodeTest {

    @Test
    void everyDeclineReasonHasAnErrorCodeOfTheSameName() {
        for (DeclineReason reason : DeclineReason.values()) {
            assertDoesNotThrow(() -> ErrorCode.valueOf(reason.name()), reason.name());
        }
    }

    @Test
    void statusMappingFollowsTheDocumentedClassification() {
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR.status());
        assertEquals(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY.status());
        assertEquals(HttpStatus.NOT_FOUND, ErrorCode.CARD_NOT_FOUND.status());
        assertEquals(HttpStatus.CONFLICT, ErrorCode.CARD_NOT_ACTIVE.status());
        assertEquals(HttpStatus.CONFLICT, ErrorCode.CARD_EXPIRED.status());
        assertEquals(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_TRANSITION.status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.INSUFFICIENT_FUNDS.status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.IDEMPOTENCY_KEY_REUSED.status());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMIT_EXCEEDED.status());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR.status());
    }

    @Test
    void customExceptionsCarryTheirCode() {
        assertEquals(ErrorCode.CARD_NOT_FOUND, new CardNotFoundException(4539148803436467L).errorCode());
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_REUSED, new IdempotencyConflictException().errorCode());
        assertEquals(ErrorCode.INVALID_REQUEST, new InvalidRequestException("x").errorCode());
        assertEquals(ErrorCode.INVALID_STATUS_TRANSITION,
                new InvalidStatusTransitionException(CardStatus.CLOSED, CardStatus.ACTIVE).errorCode());
    }
}
