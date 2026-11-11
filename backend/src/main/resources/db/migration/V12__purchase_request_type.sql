ALTER TABLE purchase_request ADD COLUMN request_type VARCHAR(12)
    CHECK (request_type IN ('PURCHASE','RESTOCK'));
