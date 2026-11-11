ALTER TABLE app_user ADD COLUMN auth_epoch INTEGER NOT NULL DEFAULT 0;
UPDATE app_user SET auth_epoch=version;
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE app_notification ADD COLUMN target_id VARCHAR(36);
ALTER TABLE app_notification ADD COLUMN target_type VARCHAR(24);
