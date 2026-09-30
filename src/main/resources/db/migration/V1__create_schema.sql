CREATE TABLE cards
(
    id              BIGINT PRIMARY KEY,
    cardholder_name VARCHAR(150)   NOT NULL,
    balance         NUMERIC(19, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    CONSTRAINT chk_card_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_card_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED'))
);

CREATE TABLE card_transactions
(
    id              UUID PRIMARY KEY,
    card_id         BIGINT         NOT NULL,
    type            VARCHAR(20)    NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    idempotency_key VARCHAR(150)   NOT NULL,
    balance_after   NUMERIC(19, 2),
    failure_code    VARCHAR(50),
    created_at      TIMESTAMPTZ    NOT NULL,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT fk_transaction_card FOREIGN KEY (card_id) REFERENCES cards (id),
    CONSTRAINT chk_transaction_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transaction_type CHECK (type IN ('TOP_UP', 'SPEND')),
    CONSTRAINT chk_transaction_status CHECK (status IN ('PENDING', 'SUCCESSFUL', 'DECLINED')),
    CONSTRAINT uk_card_idempotency UNIQUE (card_id, idempotency_key)
);

CREATE INDEX idx_transactions_card_created
    ON card_transactions (card_id, created_at DESC);
