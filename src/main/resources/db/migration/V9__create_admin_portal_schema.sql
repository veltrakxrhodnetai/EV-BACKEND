ALTER TABLE backend.stations
    ADD COLUMN IF NOT EXISTS station_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS pincode VARCHAR(12),
    ADD COLUMN IF NOT EXISTS operating_hours_type VARCHAR(20) DEFAULT 'CUSTOM',
    ADD COLUMN IF NOT EXISTS operating_hours_json TEXT,
    ADD COLUMN IF NOT EXISTS amenities_json TEXT,
    ADD COLUMN IF NOT EXISTS support_contact_number VARCHAR(20),
    ADD COLUMN IF NOT EXISTS payment_methods_json TEXT;

UPDATE backend.stations
SET station_code = 'STN-' || LPAD(id::TEXT, 4, '0')
WHERE station_code IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_backend_station_code ON backend.stations(station_code);

ALTER TABLE backend.chargers
    ADD COLUMN IF NOT EXISTS vendor_name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS model VARCHAR(120),
    ADD COLUMN IF NOT EXISTS serial_number VARCHAR(120),
    ADD COLUMN IF NOT EXISTS charger_type VARCHAR(20),
    ADD COLUMN IF NOT EXISTS ocpp_version VARCHAR(20) DEFAULT '1.6J',
    ADD COLUMN IF NOT EXISTS enabled BOOLEAN DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS communication_status VARCHAR(20) DEFAULT 'OFFLINE';

ALTER TABLE backend.tariffs
    ADD COLUMN IF NOT EXISTS idle_fee DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS time_fee DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS effective_from TIMESTAMP,
    ADD COLUMN IF NOT EXISTS effective_to TIMESTAMP,
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(20) DEFAULT 'STATION',
    ADD COLUMN IF NOT EXISTS charger_id BIGINT REFERENCES backend.chargers(id) ON DELETE CASCADE;

ALTER TABLE customer
    ADD COLUMN IF NOT EXISTS wallet_balance DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS rfid_linked BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS backend.admin_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(180) NOT NULL,
    role VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS backend.ocpp_configurations (
    id BIGSERIAL PRIMARY KEY,
    charge_point_identity VARCHAR(120) NOT NULL,
    websocket_url VARCHAR(255) NOT NULL,
    heartbeat_interval_seconds INTEGER NOT NULL DEFAULT 30,
    meter_value_interval_seconds INTEGER NOT NULL DEFAULT 30,
    allowed_ips TEXT,
    security_mode VARCHAR(30) NOT NULL DEFAULT 'NONE',
    token_value VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS backend.operator_accounts (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(180) NOT NULL,
    mobile_number VARCHAR(20) NOT NULL,
    pin_or_password_hash VARCHAR(255) NOT NULL,
    permissions_json TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS backend.operator_station_assignments (
    operator_id BIGINT NOT NULL REFERENCES backend.operator_accounts(id) ON DELETE CASCADE,
    station_id BIGINT NOT NULL REFERENCES backend.stations(id) ON DELETE CASCADE,
    PRIMARY KEY (operator_id, station_id)
);

CREATE TABLE IF NOT EXISTS backend.rfid_registry (
    id BIGSERIAL PRIMARY KEY,
    rfid_uid VARCHAR(120) NOT NULL UNIQUE,
    linked_user_id BIGINT REFERENCES customer(id) ON DELETE SET NULL,
    fleet_name VARCHAR(180),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    issued_date DATE NOT NULL DEFAULT CURRENT_DATE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS backend.admin_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_username VARCHAR(120),
    action VARCHAR(120) NOT NULL,
    resource_type VARCHAR(120) NOT NULL,
    resource_id VARCHAR(120),
    details_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS backend.ocpp_message_logs (
    id BIGSERIAL PRIMARY KEY,
    charge_point_identity VARCHAR(120),
    station_id BIGINT REFERENCES backend.stations(id) ON DELETE SET NULL,
    action VARCHAR(120) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    payload_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO backend.admin_users (username, password_hash, full_name, role, status)
SELECT 'superadmin', 'e86f78a8a3caf0b60d8e74e5942aa6d86dc150cd3c03338aef25b7d2d7e3acc7', 'Super Admin', 'SUPER_ADMIN', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM backend.admin_users WHERE username = 'superadmin');

INSERT INTO backend.admin_users (username, password_hash, full_name, role, status)
SELECT 'admin', 'e86f78a8a3caf0b60d8e74e5942aa6d86dc150cd3c03338aef25b7d2d7e3acc7', 'Network Admin', 'ADMIN', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM backend.admin_users WHERE username = 'admin');
