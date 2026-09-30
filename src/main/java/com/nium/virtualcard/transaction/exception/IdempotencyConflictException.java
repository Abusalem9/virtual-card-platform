package com.nium.virtualcard.transaction.exception;

import com.nium.virtualcard.common.exception.ApiException;
import com.nium.virtualcard.common.exception.ErrorCode;

public class IdempotencyConflictException extends ApiException {
    public IdempotencyConflictException() {
        super(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key was already used with a different request");
    }
}
