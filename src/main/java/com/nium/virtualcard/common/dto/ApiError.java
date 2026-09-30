package com.nium.virtualcard.common.dto;

import com.nium.virtualcard.common.exception.ErrorCode;
import com.nium.virtualcard.common.filter.CorrelationIdFilter;
import org.slf4j.MDC;

import java.time.Instant;

public record ApiError(String code, String message, Instant timestamp, String path, String correlationId) {

    public static ApiError of(ErrorCode code, String message, Instant timestamp, String path) {
        return of(code.name(), message, timestamp, path);
    }

    public static ApiError of(String code, String message, Instant timestamp, String path) {
        return new ApiError(code, message, timestamp, path, MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
