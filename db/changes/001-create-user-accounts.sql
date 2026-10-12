-- Reviewed account-foundation change. Apply only through a controlled migration process.
-- Existing operators must already be present. Do not execute against an unreviewed database.
CREATE TABLE user_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id INT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    bootstrap_slot INT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_account_username UNIQUE (username),
    CONSTRAINT uq_account_operator UNIQUE (operator_id),
    CONSTRAINT uq_account_bootstrap UNIQUE (bootstrap_slot),
    CONSTRAINT fk_account_operator FOREIGN KEY (operator_id) REFERENCES operators (operator_id) ON DELETE RESTRICT,
    CONSTRAINT ck_account_role_link CHECK (
        (role = 'ADMIN' AND operator_id IS NULL) OR (role = 'DRIVER' AND operator_id IS NOT NULL)
    ),
    CONSTRAINT ck_account_bootstrap CHECK (bootstrap_slot IS NULL OR (bootstrap_slot = 1 AND role = 'ADMIN'))
);
