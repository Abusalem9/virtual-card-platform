ALTER TABLE cards
    ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE cards
SET expires_at = created_at + INTERVAL '3 years'
WHERE expires_at IS NULL;

ALTER TABLE cards
    ALTER COLUMN expires_at SET NOT NULL;

CREATE INDEX idx_cards_expiration
    ON cards (status, expires_at);
