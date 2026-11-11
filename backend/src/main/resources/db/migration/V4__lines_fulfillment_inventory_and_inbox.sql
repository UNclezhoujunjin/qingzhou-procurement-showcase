CREATE TABLE request_line (
 id VARCHAR(36) PRIMARY KEY, request_id VARCHAR(36) NOT NULL REFERENCES purchase_request(id),
 product_id BIGINT NOT NULL REFERENCES product(id), supplier_id BIGINT NOT NULL REFERENCES supplier(id),
 product_name VARCHAR(120) NOT NULL, sku VARCHAR(40) NOT NULL, unit VARCHAR(16) NOT NULL,
 quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 100000), unit_price DECIMAL(14,2) NOT NULL,
 UNIQUE(request_id,product_id)
);
INSERT INTO request_line(id,request_id,product_id,supplier_id,product_name,sku,unit,quantity,unit_price)
 SELECT r.id,r.id,r.product_id,p.supplier_id,p.name,p.sku,p.unit,r.quantity,r.unit_price
 FROM purchase_request r JOIN product p ON p.id=r.product_id;
ALTER TABLE purchase_order ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE purchase_order ADD COLUMN fulfillment_status VARCHAR(24) NOT NULL DEFAULT 'OPEN';
UPDATE purchase_order SET fulfillment_status=status;
CREATE TABLE order_line (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL REFERENCES purchase_order(id),
 request_line_id VARCHAR(36) NOT NULL UNIQUE REFERENCES request_line(id), product_id BIGINT NOT NULL REFERENCES product(id),
 product_name VARCHAR(120) NOT NULL, sku VARCHAR(40) NOT NULL, unit VARCHAR(16) NOT NULL,
 quantity INTEGER NOT NULL CHECK(quantity>0), unit_price DECIMAL(14,2) NOT NULL,
 received_quantity INTEGER NOT NULL DEFAULT 0 CHECK(received_quantity>=0),
 returned_quantity INTEGER NOT NULL DEFAULT 0 CHECK(returned_quantity>=0),
 CHECK(returned_quantity<=received_quantity), CHECK(received_quantity-returned_quantity<=quantity)
);
INSERT INTO order_line(id,order_id,request_line_id,product_id,product_name,sku,unit,quantity,unit_price,received_quantity)
 SELECT o.id,o.id,r.id,r.product_id,p.name,p.sku,p.unit,r.quantity,r.unit_price,
 CASE WHEN o.status='RECEIVED' THEN r.quantity ELSE 0 END
 FROM purchase_order o JOIN purchase_request r ON r.id=o.request_id JOIN product p ON p.id=r.product_id;
CREATE TABLE fulfillment_event (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL REFERENCES purchase_order(id),
 kind VARCHAR(24) NOT NULL CHECK(kind IN ('RECEIPT','PURCHASE_RETURN')),
 reason VARCHAR(300) NOT NULL, actor_id BIGINT NOT NULL REFERENCES app_user(id),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE product ADD COLUMN stock_version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE stock_movement ADD COLUMN business_type VARCHAR(32) NOT NULL DEFAULT 'OPENING';
ALTER TABLE stock_movement ADD COLUMN returned_quantity INTEGER NOT NULL DEFAULT 0 CHECK(returned_quantity>=0);
ALTER TABLE stock_movement ADD COLUMN source_movement_id VARCHAR(36) REFERENCES stock_movement(id);
ALTER TABLE stock_movement ADD COLUMN order_line_id VARCHAR(36) REFERENCES order_line(id);
ALTER TABLE stock_movement ADD COLUMN event_id VARCHAR(36) REFERENCES fulfillment_event(id);
UPDATE stock_movement SET business_type=CASE WHEN type='IN' THEN 'PURCHASE_RECEIPT' WHEN type='OUT' THEN 'ISSUE' ELSE 'OPENING' END;
CREATE TABLE stock_count (
 id VARCHAR(36) PRIMARY KEY, number VARCHAR(40) NOT NULL UNIQUE, reason VARCHAR(300) NOT NULL,
 actor_id BIGINT NOT NULL REFERENCES app_user(id), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE stock_count_line (
 id VARCHAR(36) PRIMARY KEY, count_id VARCHAR(36) NOT NULL REFERENCES stock_count(id),
 product_id BIGINT NOT NULL REFERENCES product(id), expected_quantity INTEGER NOT NULL CHECK(expected_quantity>=0),
 counted_quantity INTEGER NOT NULL CHECK(counted_quantity>=0), difference INTEGER NOT NULL,
 UNIQUE(count_id,product_id)
);
ALTER TABLE app_user ADD COLUMN email VARCHAR(160) NOT NULL DEFAULT '';
ALTER TABLE app_user ADD COLUMN phone VARCHAR(32) NOT NULL DEFAULT '';
CREATE TABLE app_notification (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES app_user(id),
 title VARCHAR(160) NOT NULL, body VARCHAR(500) NOT NULL, path VARCHAR(160) NOT NULL,
 read_at TIMESTAMP, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_inbox_user ON app_notification(user_id,read_at,created_at);
CREATE INDEX idx_request_line_request ON request_line(request_id);
CREATE INDEX idx_order_line_order ON order_line(order_id);
CREATE INDEX idx_fulfillment_order ON fulfillment_event(order_id,created_at);
CREATE INDEX idx_movement_source ON stock_movement(source_movement_id);
