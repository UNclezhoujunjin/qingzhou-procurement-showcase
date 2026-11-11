ALTER TABLE purchase_request ADD COLUMN lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK(lifecycle_state IN ('ACTIVE','WITHDRAWN','CLOSED'));
ALTER TABLE purchase_order ADD COLUMN lifecycle_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK(lifecycle_state IN ('ACTIVE','CANCELLED','TERMINATED'));
ALTER TABLE purchase_order ADD COLUMN closure_reason VARCHAR(300) NOT NULL DEFAULT '';
ALTER TABLE purchase_order ADD COLUMN closed_at TIMESTAMP;
ALTER TABLE purchase_order ADD COLUMN closed_by BIGINT REFERENCES app_user(id);
CREATE TABLE request_draft (
 id VARCHAR(36) PRIMARY KEY,
 actor_id BIGINT NOT NULL REFERENCES app_user(id),
 payload TEXT NOT NULL,
 version INTEGER NOT NULL DEFAULT 0,
 submitted_request_id VARCHAR(36) REFERENCES purchase_request(id),
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_draft_owner ON request_draft(actor_id,updated_at);
