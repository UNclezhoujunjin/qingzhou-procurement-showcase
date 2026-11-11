ALTER TABLE product ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX idx_product_enabled_id ON product(enabled,id);
CREATE INDEX idx_request_scope_date ON purchase_request(applicant_id,created_at,id);
CREATE INDEX idx_order_state_date ON purchase_order(fulfillment_status,created_at,id);
