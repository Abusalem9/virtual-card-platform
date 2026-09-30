package com.nium.virtualcard.transaction.controller;

import com.nium.virtualcard.common.dto.ApiError;
import com.nium.virtualcard.common.dto.PageResponse;
import com.nium.virtualcard.common.exception.ErrorCode;
import com.nium.virtualcard.transaction.dto.AmountRequest;
import com.nium.virtualcard.transaction.dto.FinancialOperationResult;
import com.nium.virtualcard.transaction.dto.TransactionHistoryItem;
import com.nium.virtualcard.transaction.dto.TransactionResponse;
import com.nium.virtualcard.transaction.service.TransactionHistoryService;
import com.nium.virtualcard.transaction.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/cards")
public class TransactionController {
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransactionService transactionService;
    private final TransactionHistoryService historyService;
    private final Clock clock;

    @PostMapping("/{cardId}/spends")
    public ResponseEntity<?> spend(@PathVariable Long cardId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AmountRequest request, HttpServletRequest servletRequest) {
        return toResponse(transactionService.spend(cardId, idempotencyKey, request.amount()), servletRequest);
    }

    @PostMapping("/{cardId}/top-ups")
    public ResponseEntity<?> topUp(@PathVariable Long cardId, @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody AmountRequest request, HttpServletRequest servletRequest) {
        return toResponse(transactionService.topUp(cardId, idempotencyKey, request.amount()), servletRequest);
    }

    @GetMapping("/{cardId}/transactions")
    public PageResponse<TransactionHistoryItem> history(@PathVariable Long cardId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "" + TransactionHistoryService.DEFAULT_PAGE_SIZE) int size, @RequestParam(defaultValue = "false") boolean all) {
        return PageResponse.from(historyService.history(cardId, page, size, all).map(TransactionHistoryItem::from));
    }

    private ResponseEntity<?> toResponse(FinancialOperationResult result, HttpServletRequest request) {
        var replayed = Boolean.toString(result.replayed());

        if (result.successful()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, replayed).body(TransactionResponse.from(result.transaction()));
        }

        // Decline reasons and error codes share names by design (ErrorCodeTest enforces it).
        ErrorCode code = ErrorCode.valueOf(result.failureCode());
        var error = ApiError.of(code, result.failureMessage(), clock.instant(), request.getRequestURI());
        return ResponseEntity.status(code.status()).header(REPLAYED_HEADER, replayed).body(error);
    }
}
