-- Separate serialization locks from user rows, avoiding lock-order conflicts with audit foreign keys.
CREATE TABLE mutation_lock (
  actor_id BIGINT PRIMARY KEY REFERENCES app_user(id)
);
INSERT INTO mutation_lock(actor_id) SELECT id FROM app_user;
