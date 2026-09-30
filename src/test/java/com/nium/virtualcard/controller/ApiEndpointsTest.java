package com.nium.virtualcard.controller;

import com.nium.virtualcard.card.controller.CardController;
import com.nium.virtualcard.card.entity.Card;
import com.nium.virtualcard.card.enums.CardStatus;
import com.nium.virtualcard.card.exception.CardNotFoundException;
import com.nium.virtualcard.card.exception.InvalidStatusTransitionException;
import com.nium.virtualcard.card.service.CardService;
import com.nium.virtualcard.common.exception.GlobalExceptionHandler;
import com.nium.virtualcard.common.exception.InvalidRequestException;
import com.nium.virtualcard.common.filter.CorrelationIdFilter;
import com.nium.virtualcard.transaction.controller.TransactionController;
import com.nium.virtualcard.transaction.dto.FinancialOperationResult;
import com.nium.virtualcard.transaction.entity.CardTransaction;
import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;
import com.nium.virtualcard.transaction.exception.IdempotencyConflictException;
import com.nium.virtualcard.transaction.service.TransactionHistoryService;
import com.nium.virtualcard.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP-level contract tests: status codes, error bodies, validation and headers. No database needed.
 */
@ExtendWith(MockitoExtension.class)
class ApiEndpointsTest {
    private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");

    @Mock
    CardService cardService;
    @Mock
    TransactionService transactionService;
    @Mock
    TransactionHistoryService historyService;

    MockMvc mvc;
    Long cardId = 4539148803436467L;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mvc = MockMvcBuilders
                .standaloneSetup(new CardController(cardService), new TransactionController(transactionService, historyService, clock))
                .setControllerAdvice(new GlobalExceptionHandler(clock))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void createCardReturns201WithLocation() throws Exception {
        when(cardService.create(any())).thenReturn(card(CardStatus.ACTIVE));

        mvc.perform(post("/api/v1/cards").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\":\"Abu\",\"initialBalance\":100.00}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/cards/" + cardId))
                .andExpect(jsonPath("$.balance").value(100.00))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createCardValidatesInput() throws Exception {
        mvc.perform(post("/api/v1/cards").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\":\" \",\"initialBalance\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(cardService);
    }

    @Test
    void createCardRejectsMoreThanTwoDecimals() throws Exception {
        mvc.perform(post("/api/v1/cards").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\":\"Abu\",\"initialBalance\":1.234}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void malformedJsonUsesApiErrorShape() throws Exception {
        mvc.perform(post("/api/v1/cards").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void getCardReturnsDetails() throws Exception {
        when(cardService.get(cardId)).thenReturn(card(CardStatus.ACTIVE));

        mvc.perform(get("/api/v1/cards/{id}", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cardId))
                .andExpect(jsonPath("$.cardholderName").value("Abu"));
    }

    @Test
    void getUnknownCardReturns404() throws Exception {
        when(cardService.get(cardId)).thenThrow(new CardNotFoundException(cardId));

        mvc.perform(get("/api/v1/cards/{id}", cardId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"));
    }

    @Test
    void invalidCardIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/cards/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void unsupportedMethodKeepsStatusButUsesApiError() throws Exception {
        mvc.perform(delete("/api/v1/cards/{id}", cardId))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unexpectedFailureReturns500WithoutLeakingDetails() throws Exception {
        when(cardService.get(cardId)).thenThrow(new IllegalStateException("secret internals"));

        mvc.perform(get("/api/v1/cards/{id}", cardId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error"));
    }

    @Test
    void updateStatusReturnsUpdatedCard() throws Exception {
        when(cardService.updateStatus(cardId, CardStatus.BLOCKED)).thenReturn(card(CardStatus.BLOCKED));

        mvc.perform(patch("/api/v1/cards/{id}/status", cardId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
    }

    @Test
    void invalidStatusTransitionReturns409() throws Exception {
        when(cardService.updateStatus(cardId, CardStatus.ACTIVE))
                .thenThrow(new InvalidStatusTransitionException(CardStatus.CLOSED, CardStatus.ACTIVE));

        mvc.perform(patch("/api/v1/cards/{id}/status", cardId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void unknownStatusValueReturns400() throws Exception {
        mvc.perform(patch("/api/v1/cards/{id}/status", cardId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cardService);
    }

    @Test
    void spendSuccessReturns200() throws Exception {
        when(transactionService.spend(eq(cardId), eq("key-1"), any()))
                .thenAnswer(inv -> new FinancialOperationResult(tx(TransactionStatus.SUCCESSFUL, null), false));

        mvc.perform(post("/api/v1/cards/{id}/spends", cardId).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("SUCCESSFUL"));
    }

    @Test
    void replayIsFlaggedInHeader() throws Exception {
        when(transactionService.topUp(eq(cardId), eq("key-1"), any()))
                .thenAnswer(inv -> new FinancialOperationResult(tx(TransactionStatus.SUCCESSFUL, null), true));

        mvc.perform(post("/api/v1/cards/{id}/top-ups", cardId).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void insufficientFundsReturns422WithErrorBody() throws Exception {
        when(transactionService.spend(eq(cardId), anyString(), any())).thenAnswer(inv -> new FinancialOperationResult(tx(TransactionStatus.DECLINED, "INSUFFICIENT_FUNDS"), false));

        mvc.perform(post("/api/v1/cards/{id}/spends", cardId).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.path").value("/api/v1/cards/" + cardId + "/spends"));
    }

    @Test
    void idempotencyKeyReuseReturns422() throws Exception {
        when(transactionService.spend(eq(cardId), anyString(), any())).thenThrow(new IdempotencyConflictException());

        mvc.perform(post("/api/v1/cards/{id}/spends", cardId).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/api/v1/cards/{id}/spends", cardId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
        verifyNoInteractions(transactionService);
    }

    @Test
    void zeroAndNegativeAmountsAreRejected() throws Exception {
        for (String amount : List.of("0", "0.00", "-5.00", "1.001")) {
            mvc.perform(post("/api/v1/cards/{id}/spends", cardId).header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(transactionService);
    }

    @Test
    void missingAmountIsRejected() throws Exception {
        mvc.perform(post("/api/v1/cards/{id}/top-ups", cardId).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void historyReturnsPagedContent() throws Exception {
        var page = PageRequest.of(0, 20);
        when(historyService.history(eq(cardId), anyInt(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> new PageImpl<>(List.of(tx(TransactionStatus.SUCCESSFUL, null)), page, 1));

        mvc.perform(get("/api/v1/cards/{id}/transactions", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type").value("SPEND"))
                .andExpect(jsonPath("$.totalElements").value(1));

        // Defaults only; paging policy (caps, clamping) belongs to the service, not the controller.
        verify(historyService).history(cardId, 0, 20, false);
    }

    @Test
    void historyPassesClientParametersThroughUnchanged() throws Exception {
        when(historyService.history(eq(cardId), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        mvc.perform(get("/api/v1/cards/{id}/transactions?page=-3&size=1000", cardId)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/cards/{id}/transactions?all=true&page=4&size=5", cardId)).andExpect(status().isOk());

        verify(historyService).history(cardId, -3, 1000, false);
        verify(historyService).history(cardId, 4, 5, true);
    }

    @Test
    void inactiveCardDeclineReturns409() throws Exception {
        when(transactionService.spend(eq(cardId), anyString(), any())).thenAnswer(
                inv -> new FinancialOperationResult(tx(TransactionStatus.DECLINED, "CARD_NOT_ACTIVE"), false));

        mvc.perform(post("/api/v1/cards/{id}/spends", cardId).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARD_NOT_ACTIVE"));
    }

    @Test
    void expiredCardDeclineReturns409() throws Exception {
        when(transactionService.topUp(eq(cardId), anyString(), any())).thenAnswer(
                inv -> new FinancialOperationResult(tx(TransactionStatus.DECLINED, "CARD_EXPIRED"), true));

        mvc.perform(post("/api/v1/cards/{id}/top-ups", cardId).header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.code").value("CARD_EXPIRED"));
    }

    @Test
    void applicationLevelInvalidRequestReturns400() throws Exception {
        when(cardService.get(cardId)).thenThrow(new InvalidRequestException("bad thing"));

        mvc.perform(get("/api/v1/cards/{id}", cardId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("bad thing"));
    }

    @Test
    void stray_IllegalArgumentExceptionIsABugNotAClientError() throws Exception {
        when(cardService.get(cardId)).thenThrow(new IllegalArgumentException("internal detail"));

        mvc.perform(get("/api/v1/cards/{id}", cardId))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Unexpected error"));
    }

    @Test
    void dataIntegrityViolationReturns409WithoutDetails() throws Exception {
        when(cardService.get(cardId)).thenThrow(new DataIntegrityViolationException("constraint xyz"));

        mvc.perform(get("/api/v1/cards/{id}", cardId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                .andExpect(jsonPath("$.message").value("Request conflicts with current data"));
    }

    @Test
    void unsupportedMediaTypeKeepsStatusAndUsesApiError() throws Exception {
        mvc.perform(post("/api/v1/cards").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void errorBodyCarriesTheCorrelationId() throws Exception {
        when(cardService.get(cardId)).thenThrow(new CardNotFoundException(cardId));

        mvc.perform(get("/api/v1/cards/{id}", cardId).header("X-Correlation-Id", "trace-42"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "trace-42"))
                .andExpect(jsonPath("$.correlationId").value("trace-42"));
    }

    @Test
    void historyForUnknownCardReturns404() throws Exception {
        when(historyService.history(eq(cardId), anyInt(), anyInt(), anyBoolean())).thenThrow(new CardNotFoundException(cardId));

        mvc.perform(get("/api/v1/cards/{id}/transactions", cardId))
                .andExpect(status().isNotFound());
    }

    @Test
    void correlationIdIsPropagatedWhenSafe() throws Exception {
        when(cardService.get(cardId)).thenReturn(card(CardStatus.ACTIVE));

        mvc.perform(get("/api/v1/cards/{id}", cardId).header("X-Correlation-Id", "trace-123"))
                .andExpect(header().string("X-Correlation-Id", "trace-123"));
    }

    @Test
    void correlationIdIsGeneratedWhenMissingOrUnsafe() throws Exception {
        when(cardService.get(cardId)).thenReturn(card(CardStatus.ACTIVE));

        var generated = mvc.perform(get("/api/v1/cards/{id}", cardId)).andReturn().getResponse().getHeader("X-Correlation-Id");
        var replaced = mvc.perform(get("/api/v1/cards/{id}", cardId).header("X-Correlation-Id", "bad value with spaces"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");

        UUID.fromString(generated);
        UUID.fromString(replaced);
    }

    private Card card(CardStatus status) {
        return new Card(cardId, "Abu", new BigDecimal("100.00"), status, NOW, NOW.plusSeconds(3600));
    }

    private CardTransaction tx(TransactionStatus status, String failureCode) {
        CardTransaction tx = mock(CardTransaction.class);
        lenient().when(tx.getId()).thenReturn(UUID.randomUUID());
        lenient().when(tx.getCardId()).thenReturn(cardId);
        lenient().when(tx.getType()).thenReturn(TransactionType.SPEND);
        lenient().when(tx.getStatus()).thenReturn(status);
        lenient().when(tx.getAmount()).thenReturn(new BigDecimal("10.00"));
        lenient().when(tx.getBalanceAfter()).thenReturn(new BigDecimal("90.00"));
        lenient().when(tx.getFailureCode()).thenReturn(failureCode);
        lenient().when(tx.getCreatedAt()).thenReturn(NOW);
        lenient().when(tx.getCompletedAt()).thenReturn(NOW);
        return tx;
    }
}
