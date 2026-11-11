ALTER TABLE purchase_request ADD COLUMN approver_id BIGINT REFERENCES app_user(id);
CREATE INDEX idx_request_approver_status ON purchase_request(approver_id,status,lifecycle_state);
