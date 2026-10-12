-- Apply after 001 to the existing account schema, before deploying with ddl-auto=validate.
-- Existing accounts retain normal access. Stop the old executable before enabling new management.
ALTER TABLE user_accounts
    ADD COLUMN auth_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN temporary_password_expires_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_account_revision CHECK (auth_revision >= 0),
    ADD CONSTRAINT ck_account_password_setup CHECK (
        (password_change_required = TRUE AND temporary_password_expires_at IS NOT NULL)
        OR (password_change_required = FALSE AND temporary_password_expires_at IS NULL)
    );
CREATE TABLE account_audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_account_id BIGINT NOT NULL,
    target_account_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL
);
