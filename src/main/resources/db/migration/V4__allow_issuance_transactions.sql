ALTER TABLE card_transactions
    DROP CONSTRAINT chk_transaction_type;

ALTER TABLE card_transactions
    ADD CONSTRAINT chk_transaction_type CHECK (type IN ('ISSUANCE', 'TOP_UP', 'SPEND'));
