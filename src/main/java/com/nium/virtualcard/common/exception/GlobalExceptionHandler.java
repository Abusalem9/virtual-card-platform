package com.nium.virtualcard.common.exception;

import com.nium.virtualcard.common.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.util.stream.Collectors;

/**
 * Translates every failure into an {@link ApiError}. Deliberate failures carry their own {@link ErrorCode}
 * (and therefore status); framework errors are mapped explicitly; anything else is a bug and becomes a
 * generic 500 whose details are logged, never returned.
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler {
    private final Clock clock;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> apiException(ApiException exception, HttpServletRequest request) {
        return error(exception.errorCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException exception, HttpServletRequest request) {
        ErrorCode code = "Idempotency-Key".equalsIgnoreCase(exception.getHeaderName())
                ? ErrorCode.MISSING_IDEMPOTENCY_KEY : ErrorCode.MISSING_HEADER;
        return error(code, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(ErrorCode.VALIDATION_ERROR, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadableBody(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return error(ErrorCode.MALFORMED_REQUEST, "Request body is missing or malformed", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return error(ErrorCode.INVALID_PARAMETER, "Invalid value for parameter '" + exception.getName() + "'", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("Data integrity violation path={}", request.getRequestURI(), exception);
        return error(ErrorCode.DATA_CONFLICT, "Request conflicts with current data", request);
    }

    /**
     * Catch-all: Spring MVC's own errors keep their status, everything else is an unexpected bug.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse errorResponse) {
            return springError(errorResponse, request);
        }
        log.error("Unhandled exception path={}", request.getRequestURI(), exception);
        return error(ErrorCode.INTERNAL_ERROR, "Unexpected error", request);
    }

    /**
     * Keeps Spring MVC's own status codes (404, 405, 415, ...) while using the ApiError body shape.
     */
    private ResponseEntity<ApiError> springError(ErrorResponse exception, HttpServletRequest request) {
        HttpStatusCode status = exception.getStatusCode();
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = resolved != null ? resolved.name() : "HTTP_" + status.value();
        return ResponseEntity.status(status)
                .body(ApiError.of(code, exception.getBody().getDetail(), clock.instant(), request.getRequestURI()));
    }

    private ResponseEntity<ApiError> error(ErrorCode code, String message, HttpServletRequest request) {
        return ResponseEntity.status(code.status())
                .body(ApiError.of(code, message, clock.instant(), request.getRequestURI()));
    }
}
