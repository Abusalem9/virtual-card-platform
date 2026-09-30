# Virtual Card Issuance Platform

A Spring Boot service for issuing virtual cards, topping them up, spending from them and reading their history. The main concerns were correct balances under concurrent load and safe retries, so those parts are covered in the most detail below.

Stack: Java 21, Spring Boot 3.3, Spring Data JPA with a few native SQL statements, PostgreSQL, Flyway, Micrometer/Prometheus, Testcontainers.

## Running it

You need Java 21 and Docker.

```bash
docker compose up -d postgres
./gradlew bootRun          # gradlew.bat bootRun on Windows
```

The API is on `http://localhost:8080` and Swagger UI on `/swagger-ui.html`. The defaults (`postgres` / `postgres` on `localhost:5432/virtual_cards`) match `docker-compose.yml`. Override them with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.

To run the app and the database together: `docker compose up --build`.

Tests: `./gradlew clean test`. The integration tests start a PostgreSQL container, so Docker has to be running. Without Docker they are skipped and Gradle still reports success, so check the report for skipped tests.

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/cards` | body: `cardholderName`, `initialBalance`, optional `expiresAt` |
| GET | `/api/v1/cards/{cardId}` | card details and balance |
| POST | `/api/v1/cards/{cardId}/top-ups` | needs an `Idempotency-Key` header, body: `amount` |
| POST | `/api/v1/cards/{cardId}/spends` | needs an `Idempotency-Key` header, body: `amount` |
| PATCH | `/api/v1/cards/{cardId}/status` | body: `status` (`ACTIVE`, `BLOCKED`, `CLOSED`) |
| GET | `/api/v1/cards/{cardId}/transactions` | `page`, `size` (default 20, max 100) or `all=true` |

The card id is a random 16-digit number with a Luhn check digit (for example `4539148803436467`), stored as `BIGINT`. It is only an identifier. It is not a real card number and nothing here handles card data. A non-numeric id in a path returns `400`.

History is newest first. `all=true` returns the whole history as a single page, capped at 10,000 entries so one very large card can't exhaust memory.

Status changes: `ACTIVE` and `BLOCKED` can switch between each other, and either can move to `CLOSED`. `CLOSED` is final.

When a card is issued with a non-zero balance, the opening balance is recorded as the first history entry (type `ISSUANCE`), so the history always adds up to the balance.

Every failure has the same body, and `correlationId` matches the `X-Correlation-Id` response header and the logs:

```json
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "Card does not have sufficient balance",
  "timestamp": "2026-09-29T12:00:00Z",
  "path": "/api/v1/cards/4539148803436467/spends",
  "correlationId": "5d0c7a1e-..."
}
```

| Status | Codes |
|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `INVALID_REQUEST`, `MISSING_IDEMPOTENCY_KEY`, `MISSING_HEADER` |
| 404 | `CARD_NOT_FOUND` |
| 409 | `CARD_NOT_ACTIVE`, `CARD_EXPIRED`, `INVALID_STATUS_TRANSITION`, `DATA_CONFLICT` |
| 422 | `INSUFFICIENT_FUNDS`, `IDEMPOTENCY_KEY_REUSED` |
| 429 | `RATE_LIMIT_EXCEEDED` (with `Retry-After`) |
| 500 | `INTERNAL_ERROR` (details are logged, never returned) |

## How it works

### Balances under concurrency

A spend is not a read-then-write in Java. The check and the update are one SQL statement, so two requests can't both see the same balance:

```sql
UPDATE cards
SET balance = balance - :amount
WHERE id = :cardId
  AND status = 'ACTIVE'
  AND expires_at > :now
  AND balance >= :amount;
```

If it updates no rows, the service loads the card to work out why (expired, not active or not enough money) and records a declined transaction. Top-ups use the same pattern with an increment. The database also has `CHECK (balance >= 0)` as a last line of defence.

Because every change to one card takes that card's row lock, throughput scales across cards, not within a single card. That is fine for this use case and is noted under scaling.

### Idempotency

Each spend or top-up first inserts a transaction row with status `PENDING`:

```sql
INSERT ... ON CONFLICT (card_id, idempotency_key) DO NOTHING
```

Only one of several concurrent requests with the same key gets to insert, and the others return that request's stored result. A replay is only accepted if the type and amount match the original. Otherwise it returns `422 IDEMPOTENCY_KEY_REUSED`.

A declined attempt is stored under its key like any other result, so retrying the same key returns the same decline even if the card has been topped up since. A key identifies one logical request. To try again, the client uses a new key.

Reserving the key, changing the balance and completing the transaction all happen in one database transaction, so other clients never see a `PENDING` row today. The status stays in the model for when authorization and capture flows need a real pending state.

### Expiry

Cards have an `expiresAt` (default: three years after creation). A scheduled job closes expired cards every minute. The SQL above also checks `expires_at > now`, so an expired card is refused immediately, even if the job hasn't run yet. Expiry closes the card because the required statuses are only active, blocked and closed.

### Audit events

After a financial transaction or a card change commits, an event is published and handled by an `@Async` `@TransactionalEventListener(AFTER_COMMIT)` listener. It writes a row to `audit_events` in its own transaction, off the request thread. The money path doesn't wait for it.

Delivery is in-process and best effort. If the app crashes between the commit and the audit write, that row is lost. A transactional outbox would close that gap (see below).

### Rate limiting

Every endpoint except the health probes goes through a per-client sliding-window limiter. The default is 120 requests per minute, configurable with `RATE_LIMIT_RPM`. The client is the remote address. Set `RATE_LIMIT_TRUST_FORWARDED_FOR=true` only behind a proxy you control, because the header can be spoofed. Tracked clients are capped at 50,000 and extra clients share one bucket, so memory stays bounded.

State is in memory, so the limit applies per instance.

## Code layout

Feature modules first, then layers inside each module:

```text
com.nium.virtualcard
├── card          controller, service, scheduler, repository, entity, enums, dto, event, exception
├── transaction   controller, service, listener, repository, entity, enums, dto, event, exception
├── audit         listener, repository, entity
└── common        config, dto, exception, filter, util
```

- `controller` handles HTTP and validation only.
- `service` holds the use cases and takes plain commands.
- `listener` and `scheduler` react to events and timers.
- `repository` holds the Spring Data interfaces and the native statements.
- `entity` and `enums` are the persistent model and its rules. Decline rules are on `Card` and transition rules are on `CardStatus`.
- `dto` and `event` are records.

Modules depend in one direction: `audit` depends on `transaction` and `card`, and `transaction` depends on `card`. `card` publishes events and knows nothing about the others. `ArchitectureTest` (ArchUnit) fails the build if a layer rule is broken or the modules form a cycle.

Entities have no setters. Balance and status only change through the guarded SQL, so saving a whole entity can't overwrite a concurrent money movement.

## Observability

- Every request gets an `X-Correlation-Id` (taken from the request if it matches `[A-Za-z0-9._-]{1,64}`, otherwise generated). It is in the log pattern and in error bodies.
- Actuator exposes `health`, `info`, `metrics` and `prometheus` (`/actuator/prometheus`).
- Custom metrics: `virtual_card_cards_created_total`, `virtual_card_status_changes_total`, `virtual_card_transactions_total`, `virtual_card_transaction_duration`, `virtual_card_idempotent_replays_total`, `virtual_card_expired_total` and `virtual_card_rate_limit_rejected_total`.
- Card issuance, status changes and every financial outcome are logged and written to `audit_events`.

## Tests

- Unit tests with mocks: `CardServiceTest`, `TransactionServiceTest`, `TransactionHistoryServiceTest`, `IssuanceRecorderTest`, `CardNumberGeneratorTest`, `RateLimitFilterTest`.
- `ApiEndpointsTest` checks the HTTP contract with MockMvc: status codes, error bodies, validation, headers and paging limits.
- `ArchitectureTest` covers the layering rules.
- `VirtualCardIntegrationTest` runs against a real PostgreSQL 16 container: 100 concurrent spends against a finite balance, concurrent top-ups, concurrent requests with the same idempotency key, status changes racing with top-ups, expiry, audit persistence and a full HTTP flow.

The main concurrency test starts with `1000.00` and sends 100 simultaneous spends of `20.00`. Exactly 50 must succeed, 50 must be declined and the final balance must be `0.00`. I used PostgreSQL in the tests instead of H2 because the guarantees depend on real locking and `ON CONFLICT` behaviour. JaCoCo reports coverage on each run (`build/reports/jacoco/test/html`).

## What happens when things fail

- A client retries after a timeout: the same key returns the original result.
- Two requests race with the same key: one runs and the other returns its result.
- Concurrent spends exceed the balance: only the affordable ones succeed and the rest are recorded as declined.
- The app crashes mid-request: the operation is one database transaction, so it is fully applied or fully rolled back, and a retry with the same key is safe.
- The audit queue is full or the app shuts down: the executor is bounded (4 to 8 threads, queue of 1000) and drains queued work on graceful shutdown.
- The database is down: requests fail with `500` and health reports `DOWN`.
- A client floods the API: it gets `429` with `Retry-After` and other clients are unaffected.

## Scaling

The service is stateless, and correctness comes from the database, not from JVM locks, so several instances can share one PostgreSQL. Next steps, roughly in order:

1. Tune the Hikari pool (20 connections today) against measured load.
2. Send history reads to read replicas and keep balance changes on the primary.
3. Partition or archive large transaction and audit tables. Idempotency keys are kept forever today. A real system would expire them after a day or two.
4. Move rate limiting to an API gateway or Redis so limits are shared across instances.
5. Add tracing, JSON logs and dashboards on top of the existing metrics.
6. Load test with k6 or Gatling to find real p95 and p99 numbers.

The limit to be aware of is a single hot card. All its operations queue on one row lock. If that mattered, the options are queueing operations per card, or an append-only ledger with periodically materialised balances.

## Moving to microservices or an event-driven design

The module boundaries are where the service would split. A rough target:

```text
API Gateway -> Card service -> Ledger service (PostgreSQL)
                                 |
                          transactional outbox -> Kafka -> audit, notifications, fraud, analytics
```

Events already flow between modules through Spring events, so the first step would be writing them to an outbox table in the same transaction and publishing them to Kafka from there. That removes the audit gap described above without needing distributed transactions.

For a real financial product, `cards.balance` would become a cached projection over an immutable double-entry ledger. That makes reversals, refunds, authorisation and capture, settlement and reconciliation much easier.

External systems such as a card processor would sit behind an interface (for example `CardProcessor`) with adapters, never called directly from controllers or entities. The adapters would add timeouts, retries with backoff, circuit breakers and the provider's own idempotency keys.

## Trade-offs and what I left out

- **Modular monolith** instead of several services: it keeps card, transaction and idempotency changes in one database transaction, which is the hard part here.
- **JPA plus native SQL** instead of jOOQ: the balance and idempotency statements are hand-written SQL anyway, and JPA covers the simple reads and inserts. jOOQ would add a dependency and code generation for little gain at this size.
- **In-process events** instead of Kafka: enough to show non-critical work running off the request path. Kafka would add operations work without improving the core correctness.
- **In-memory rate limiter**: simple and bounded, but per instance.
- **No currency**: the model only has an amount and a balance. A real system needs currency-aware money types.
- **No authentication**: there is no cardholder ownership check. Anyone who knows a card id can use it.

With more time I would add:

- a double-entry ledger and authorise/capture/reverse/refund types,
- multi-currency support,
- authentication and ownership rules,
- the outbox and Kafka,
- distributed rate limiting,
- fraud checks and reconciliation jobs,
- contract and load tests,
- audit records for denied and read requests, since only state changes and financial outcomes are audited today.

## Learning notes

Two things in this project were new to me: Testcontainers and Spring's application events.

**Testcontainers.** The concurrency and idempotency guarantees depend on PostgreSQL's row locking and `ON CONFLICT`, which an in-memory database like H2 doesn't reproduce faithfully. `VirtualCardIntegrationTest` starts a PostgreSQL 16 container, and Flyway runs the real migrations against it. The scenario that shows it works is 100 simultaneous spends of `20.00` against a `1000.00` balance: exactly 50 succeed, 50 are declined and the balance ends at `0.00`. One thing to watch is that the suite is skipped when Docker isn't running, so a green build on a machine without Docker doesn't prove those tests ran.

**Spring application events.** Events keep work that isn't part of the money movement away from the code that moves the money, and they let modules react without knowing each other. There are two kinds of listener here:

- `IssuanceRecorder` is a plain listener. It runs inside the issuing transaction, so a new card and its opening history entry commit together or not at all.
- `AsyncAuditListener` uses `@Async` with `@TransactionalEventListener(AFTER_COMMIT)`. It only runs once the business transaction has committed, on another thread, and writes the audit row in its own transaction.

The catch is that delivery is in-process and best effort, so an audit row can be lost if the app crashes between the commit and the write. That is why the outbox is the first step in the move to an event-driven design. `completedTransactionIsAuditedAsynchronously` and `cardLifecycleIsAuditedAsynchronously` in the integration suite check the behaviour.
