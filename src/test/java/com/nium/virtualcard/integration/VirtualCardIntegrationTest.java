package com.nium.virtualcard.integration;

import com.nium.virtualcard.audit.repository.AuditEventRepository;
import com.nium.virtualcard.card.dto.CreateCardCommand;
import com.nium.virtualcard.card.entity.Card;
import com.nium.virtualcard.card.enums.CardStatus;
import com.nium.virtualcard.card.exception.InvalidStatusTransitionException;
import com.nium.virtualcard.card.repository.CardRepository;
import com.nium.virtualcard.card.scheduler.CardExpirationScheduler;
import com.nium.virtualcard.card.service.CardService;
import com.nium.virtualcard.transaction.enums.TransactionStatus;
import com.nium.virtualcard.transaction.enums.TransactionType;
import com.nium.virtualcard.transaction.exception.IdempotencyConflictException;
import com.nium.virtualcard.transaction.repository.TransactionRepository;
import com.nium.virtualcard.transaction.service.TransactionHistoryService;
import com.nium.virtualcard.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.card-expiration.scan-interval-ms=3600000")
class VirtualCardIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("virtual_cards")
            .withUsername("postgres")
            .withPassword("postgres");
    @Autowired
    CardService cardService;
    @Autowired
    TransactionService transactionService;
    @Autowired
    TransactionHistoryService historyService;
    @Autowired
    CardRepository cardRepository;
    @Autowired
    TransactionRepository transactionRepository;
    @Autowired
    AuditEventRepository auditEventRepository;
    @Autowired
    CardExpirationScheduler cardExpirationScheduler;
    @Autowired
    MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private Card newCard(String name, String balance) {
        return cardService.create(new CreateCardCommand(name, new BigDecimal(balance), null));
    }

    @BeforeEach
    void clean() {
        auditEventRepository.deleteAll();
        transactionRepository.deleteAll();
        cardRepository.deleteAll();
    }

    @Test
    void topUpAndSpendAreTracked() {
        var card = newCard("Test", "100.00");

        var topUp = transactionService.topUp(card.getId(), "topup-1", new BigDecimal("50.00"));
        var spend = transactionService.spend(card.getId(), "spend-1", new BigDecimal("30.00"));

        assertTrue(topUp.successful());
        assertTrue(spend.successful());
        assertEquals(new BigDecimal("120.00"), cardRepository.findById(card.getId()).orElseThrow().getBalance());
        // issuance + top-up + spend
        assertEquals(3, transactionRepository.findByCardId(card.getId(), Pageable.unpaged()).getTotalElements());
    }

    @Test
    void duplicateIdempotencyKeyChangesBalanceOnlyOnce() {
        var card = newCard("Test", "100.00");

        var first = transactionService.spend(card.getId(), "same-key", new BigDecimal("25.00"));
        var second = transactionService.spend(card.getId(), "same-key", new BigDecimal("25.00"));

        assertTrue(first.successful());
        assertTrue(second.successful());
        assertTrue(second.replayed());
        assertEquals(first.transaction().getId(), second.transaction().getId());
        assertEquals(new BigDecimal("75.00"), cardRepository.findById(card.getId()).orElseThrow().getBalance());
    }

    @Test
    void concurrentSpendsNeverTakeBalanceBelowZero() throws Exception {
        var card = newCard("Concurrent", "1000.00");
        int attempts = 100;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            int n = i;
            futures.add(executor.submit(() -> {
                start.await();
                return transactionService.spend(card.getId(), "spend-" + n, new BigDecimal("20.00")).successful();
            }));
        }

        start.countDown();
        int successful = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) successful++;
        }
        executor.shutdownNow();

        var finalCard = cardRepository.findById(card.getId()).orElseThrow();
        assertEquals(50, successful);
        assertEquals(new BigDecimal("0.00"), finalCard.getBalance());
        assertEquals(CardStatus.ACTIVE, finalCard.getStatus());
        var history = transactionRepository.findByCardId(card.getId(), Pageable.unpaged());
        assertEquals(50, history.stream().filter(t -> t.getType() == TransactionType.SPEND && t.getStatus() == TransactionStatus.SUCCESSFUL).count());
        assertEquals(50, history.stream().filter(t -> t.getStatus() == TransactionStatus.DECLINED).count());
    }

    @Test
    void concurrentTopUpsDoNotLoseUpdates() throws Exception {
        var card = newCard("Concurrent", "0.00");
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            int n = i;
            futures.add(executor.submit(() -> {
                start.await();
                transactionService.topUp(card.getId(), "topup-" + n, new BigDecimal("10.00"));
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(new BigDecimal("1000.00"), cardRepository.findById(card.getId()).orElseThrow().getBalance());
    }

    @Test
    void concurrentSameIdempotencyKeyIsAppliedOnce() throws Exception {
        var card = newCard("Concurrent", "1000.00");
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                transactionService.spend(card.getId(), "one-key", new BigDecimal("100.00"));
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(new BigDecimal("900.00"), cardRepository.findById(card.getId()).orElseThrow().getBalance());
        assertEquals(1, transactionRepository.findByCardId(card.getId(), Pageable.unpaged()).stream()
                .filter(t -> t.getType() == TransactionType.SPEND).count());
    }

    @Test
    void blockedCardCannotSpendOrTopUp() {
        var created = newCard("Blocked", "100.00");
        cardService.updateStatus(created.getId(), CardStatus.BLOCKED);
        Card card = cardRepository.findById(created.getId()).orElseThrow();

        var spend = transactionService.spend(card.getId(), "blocked-spend", new BigDecimal("10.00"));
        var topUp = transactionService.topUp(card.getId(), "blocked-topup", new BigDecimal("10.00"));

        assertFalse(spend.successful());
        assertEquals("CARD_NOT_ACTIVE", spend.failureCode());
        assertFalse(topUp.successful());
        assertEquals("CARD_NOT_ACTIVE", topUp.failureCode());
        assertEquals(new BigDecimal("100.00"), cardRepository.findById(card.getId()).orElseThrow().getBalance());
    }

    @Test
    void expirationSchedulerClosesExpiredCardsAndFinancialOperationsDecline() {
        Instant now = Instant.now();
        Card expired = new Card(4539148803436467L, "Expired", new BigDecimal("100.00"), CardStatus.ACTIVE,
                now.minusSeconds(3600), now.minusSeconds(1));
        cardRepository.saveAndFlush(expired);

        var result = transactionService.spend(expired.getId(), "expired-spend", new BigDecimal("10.00"));
        assertFalse(result.successful());
        assertEquals("CARD_EXPIRED", result.failureCode());

        cardExpirationScheduler.expireCards();
        assertEquals(CardStatus.CLOSED, cardRepository.findById(expired.getId()).orElseThrow().getStatus());
    }

    @Test
    void completedTransactionIsAuditedAsynchronously() throws Exception {
        var card = newCard("Audit", "100.00");
        var result = transactionService.spend(card.getId(), "audit-spend", new BigDecimal("10.00"));
        UUID transactionId = result.transaction().getId();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (auditEventRepository.countByTransactionId(transactionId) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(1, auditEventRepository.countByTransactionId(transactionId));
    }

    @Test
    void initialBalanceAppearsAsFirstHistoryEntry() {
        var card = newCard("Funded", "40.00");

        var history = historyService.history(card.getId(), PageRequest.of(0, 10));

        assertEquals(1, history.getTotalElements());
        assertEquals(TransactionType.ISSUANCE, history.getContent().get(0).getType());
        assertEquals(new BigDecimal("40.00"), history.getContent().get(0).getBalanceAfter());
    }

    @Test
    void zeroBalanceCardHasEmptyHistory() {
        var card = newCard("Empty", "0.00");

        assertEquals(0, historyService.history(card.getId(), PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void insufficientFundsIsDeclinedAndRecorded() {
        var card = newCard("Poor", "10.00");

        var result = transactionService.spend(card.getId(), "too-much", new BigDecimal("10.01"));

        assertFalse(result.successful());
        assertEquals("INSUFFICIENT_FUNDS", result.failureCode());
        assertEquals(TransactionStatus.DECLINED, result.transaction().getStatus());
        assertEquals(new BigDecimal("10.00"), cardRepository.findById(card.getId()).orElseThrow().getBalance());
    }

    @Test
    void reusingKeyWithDifferentRequestIsRejectedAndChangesNothing() {
        var card = newCard("Reuse", "100.00");
        transactionService.spend(card.getId(), "reused", new BigDecimal("10.00"));

        assertThrows(IdempotencyConflictException.class,
                () -> transactionService.spend(card.getId(), "reused", new BigDecimal("20.00")));
        assertThrows(IdempotencyConflictException.class,
                () -> transactionService.topUp(card.getId(), "reused", new BigDecimal("10.00")));

        assertEquals(new BigDecimal("90.00"), cardRepository.findById(card.getId()).orElseThrow().getBalance());
    }

    @Test
    void sameKeyOnDifferentCardsIsIndependent() {
        var a = newCard("A", "100.00");
        var b = newCard("B", "100.00");

        assertTrue(transactionService.spend(a.getId(), "shared", new BigDecimal("10.00")).successful());
        var second = transactionService.spend(b.getId(), "shared", new BigDecimal("10.00"));

        assertTrue(second.successful());
        assertFalse(second.replayed());
    }

    @Test
    void historyIsNewestFirstAndPaged() {
        var card = newCard("Paged", "100.00");
        for (int i = 0; i < 5; i++) {
            transactionService.spend(card.getId(), "s-" + i, new BigDecimal("1.00"));
        }

        var page = historyService.history(card.getId(),
                PageRequest.of(0, 4, Sort.by(Sort.Direction.DESC, "createdAt", "id")));

        assertEquals(6, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
        assertEquals(4, page.getContent().size());
        for (int i = 1; i < page.getContent().size(); i++) {
            assertFalse(page.getContent().get(i).getCreatedAt().isAfter(page.getContent().get(i - 1).getCreatedAt()));
        }
    }

    @Test
    void statusChangeDoesNotClobberConcurrentBalanceUpdates() throws Exception {
        var card = newCard("Racy", "0.00");
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            int n = i;
            futures.add(executor.submit(() -> {
                start.await();
                transactionService.topUp(card.getId(), "race-" + n, new BigDecimal("1.00"));
                return null;
            }));
        }
        futures.add(executor.submit(() -> {
            start.await();
            cardService.updateStatus(card.getId(), CardStatus.BLOCKED);
            cardService.updateStatus(card.getId(), CardStatus.ACTIVE);
            return null;
        }));

        start.countDown();
        for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        var finalCard = cardRepository.findById(card.getId()).orElseThrow();
        BigDecimal credited = transactionRepository.findByCardId(card.getId(), Pageable.unpaged()).stream()
                .filter(t -> t.getStatus() == TransactionStatus.SUCCESSFUL)
                .map(t -> t.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Top-ups attempted while blocked are declined; the balance must equal the sum of successful credits.
        assertEquals(0, credited.compareTo(finalCard.getBalance()));
        assertEquals(CardStatus.ACTIVE, finalCard.getStatus());
    }

    @Test
    void closedCardCannotBeReopened() {
        var card = newCard("Closing", "10.00");
        cardService.updateStatus(card.getId(), CardStatus.CLOSED);

        assertThrows(InvalidStatusTransitionException.class,
                () -> cardService.updateStatus(card.getId(), CardStatus.ACTIVE));
    }

    @Test
    void cardLifecycleIsAuditedAsynchronously() throws Exception {
        var card = newCard("Lifecycle", "10.00");
        cardService.updateStatus(card.getId(), CardStatus.BLOCKED);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (auditEventRepository.count() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(2, auditEventRepository.findAll().stream()
                .filter(e -> card.getId().equals(e.getCardId()) && e.getTransactionId() == null).count());
    }

    @Test
    void httpFlowEndToEnd() throws Exception {
        String body = mockMvc.perform(post("/api/v1/cards").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardholderName\":\"E2E\",\"initialBalance\":100.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String cardId = body.replaceAll(".*\"id\":(\\d{16}).*", "$1");

        mockMvc.perform(post("/api/v1/cards/{id}/spends", cardId).header("Idempotency-Key", "e2e-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(70.00));

        mockMvc.perform(post("/api/v1/cards/{id}/spends", cardId).header("Idempotency-Key", "e2e-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mockMvc.perform(post("/api/v1/cards/{id}/spends", cardId).header("Idempotency-Key", "e2e-2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":500.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        mockMvc.perform(get("/api/v1/cards/{id}", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(70.00));

        mockMvc.perform(get("/api/v1/cards/{id}/transactions", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        mockMvc.perform(get("/api/v1/cards/{id}", 4111111111111111L))
                .andExpect(status().isNotFound());
    }
}
