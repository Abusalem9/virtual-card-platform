CREATE TABLE audit_events
(
    id             UUID PRIMARY KEY,
    event_type     VARCHAR(80) NOT NULL,
    card_id        BIGINT,
    transaction_id UUID,
    details        VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_events_created
    ON audit_events (created_at DESC);

CREATE INDEX idx_audit_events_card
    ON audit_events (card_id, created_at DESC);
