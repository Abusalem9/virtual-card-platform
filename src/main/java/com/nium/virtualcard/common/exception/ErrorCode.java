package com.nium.virtualcard.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The single catalogue of error codes the API can return, each with the HTTP status it is sent with.
 * Clients branch on the code; the status tells generic HTTP tooling what class of problem it was.
 */
public enum ErrorCode {
    // 400: the request itself is wrong
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST),
    MISSING_HEADER(HttpStatus.BAD_REQUEST),

    // 404
    CARD_NOT_FOUND(HttpStatus.NOT_FOUND),

    // 409: the request is fine but conflicts with the current state of the resource
    CARD_NOT_ACTIVE(HttpStatus.CONFLICT),
    CARD_EXPIRED(HttpStatus.CONFLICT),
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT),
    DATA_CONFLICT(HttpStatus.CONFLICT),

    // 422: well-formed and allowed in general, but rejected by a business rule
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),

    // 429 / 500
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
