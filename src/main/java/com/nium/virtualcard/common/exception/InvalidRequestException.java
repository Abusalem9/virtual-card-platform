package com.nium.virtualcard.common.exception;

public class InvalidRequestException extends ApiException {
    public InvalidRequestException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
