-- Complete fresh-install schema. Existing databases must be checked and explicitly baselined.
-- Includes the reviewed account changes 001/002; do not apply those files again.
CREATE TABLE warehouses (
    warehouse_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    address TEXT NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE operators (
    operator_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    email VARCHAR(100) NOT NULL,
    vehicle_type VARCHAR(50),
    max_capacity DOUBLE,
    warehouse_id INT,
    availability_status ENUM('AVAILABLE','OFF_DUTY','ON_DELIVERY','UNAVAILABLE') NOT NULL,
    CONSTRAINT uq_operator_email UNIQUE(email),
    CONSTRAINT fk_operator_warehouse FOREIGN KEY(warehouse_id) REFERENCES warehouses(warehouse_id)
) ENGINE=InnoDB;

CREATE TABLE recipients (
    recipient_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    address TEXT NOT NULL,
    latitude DECIMAL(9,6) NOT NULL,
    longitude DECIMAL(9,6) NOT NULL,
    contact_number VARCHAR(20),
    priority_level ENUM('HIGH','LOW','MEDIUM') NOT NULL,
    created_at DATETIME(6)
) ENGINE=InnoDB;

CREATE TABLE deliveries (
    delivery_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_id INT NOT NULL,
    operator_id INT,
    scheduled_date DATE,
    start_time_window TIME(6),
    end_time_window TIME(6),
    priority_level ENUM('HIGH','LOW','MEDIUM') NOT NULL,
    delivery_status ENUM('CANCELLED','COMPLETED','IN_PROGRESS','PENDING') NOT NULL,
    special_instructions VARCHAR(255),
    from_prev_distance_km DOUBLE,
    from_prev_duration_text VARCHAR(64),
    from_prev_duration_seconds INT,
    CONSTRAINT fk_delivery_recipient FOREIGN KEY(recipient_id) REFERENCES recipients(recipient_id),
    CONSTRAINT fk_delivery_operator FOREIGN KEY(operator_id) REFERENCES operators(operator_id)
) ENGINE=InnoDB;

CREATE TABLE routes (
    route_id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operator_id INT NOT NULL,
    scheduled_date DATE NOT NULL,
    route_status ENUM('COMPLETED','IN_PROGRESS','PLANNED') NOT NULL,
    CONSTRAINT fk_route_operator FOREIGN KEY(operator_id) REFERENCES operators(operator_id)
) ENGINE=InnoDB;

CREATE TABLE route_deliveries (
    route_id INT NOT NULL,
    delivery_order INT NOT NULL,
    delivery_id INT NOT NULL,
    PRIMARY KEY(delivery_order, route_id),
    CONSTRAINT uq_route_delivery UNIQUE(delivery_id),
    CONSTRAINT fk_route_delivery_route FOREIGN KEY(route_id) REFERENCES routes(route_id),
    CONSTRAINT fk_route_delivery_delivery FOREIGN KEY(delivery_id) REFERENCES deliveries(delivery_id)
) ENGINE=InnoDB;

CREATE TABLE user_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id INT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    bootstrap_slot INT,
    auth_revision BIGINT NOT NULL DEFAULT 0,
    password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    temporary_password_expires_at DATETIME(6),
    CONSTRAINT uq_account_username UNIQUE(username),
    CONSTRAINT uq_account_operator UNIQUE(operator_id),
    CONSTRAINT uq_account_bootstrap UNIQUE(bootstrap_slot),
    CONSTRAINT fk_account_operator FOREIGN KEY(operator_id) REFERENCES operators(operator_id) ON DELETE RESTRICT,
    CONSTRAINT ck_account_role_link CHECK((role = 'ADMIN' AND operator_id IS NULL) OR (role = 'DRIVER' AND operator_id IS NOT NULL)),
    CONSTRAINT ck_account_bootstrap CHECK(bootstrap_slot IS NULL OR (bootstrap_slot = 1 AND role = 'ADMIN')),
    CONSTRAINT ck_account_revision CHECK(auth_revision >= 0),
    CONSTRAINT ck_account_password_setup CHECK((password_change_required = TRUE AND temporary_password_expires_at IS NOT NULL) OR (password_change_required = FALSE AND temporary_password_expires_at IS NULL))
) ENGINE=InnoDB;

CREATE TABLE account_audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_account_id BIGINT NOT NULL,
    target_account_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
