-- Create backend schema if it doesn't exist (should already exist from V4, but being safe)
CREATE SCHEMA IF NOT EXISTS backend;

-- Create backend.charging_sessions table if it doesn't exist
-- This ensures the table exists before we try to reference it
CREATE TABLE IF NOT EXISTS backend.charging_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    operator_id BIGINT,
    station_id BIGINT,
    charger_id BIGINT NOT NULL REFERENCES backend.chargers(id) ON DELETE RESTRICT,
    connector_id BIGINT REFERENCES backend.connectors(id) ON DELETE SET NULL,
    connector_no INTEGER NOT NULL,
    ocpp_transaction_id INTEGER,
    vehicle_number VARCHAR(64),
    phone_number VARCHAR(32),
    started_by VARCHAR(32) NOT NULL,
    limit_type VARCHAR(32),
    limit_value DOUBLE PRECISION,
    meter_start BIGINT,
    meter_stop BIGINT,
    energy_consumed_kwh DOUBLE PRECISION,
    base_amount DOUBLE PRECISION,
    gst_amount DOUBLE PRECISION,
    total_amount DOUBLE PRECISION,
    platform_fee DOUBLE PRECISION,
    owner_revenue DOUBLE PRECISION,
    payment_mode VARCHAR(32) NOT NULL,
    payment_status VARCHAR(64) NOT NULL,
    status VARCHAR(30) DEFAULT 'PENDING',
    connector_verified BOOLEAN DEFAULT FALSE,
    connector_verified_at TIMESTAMP,
    preauth_id VARCHAR(120),
    preauth_amount DOUBLE PRECISION,
    refund_amount DOUBLE PRECISION DEFAULT 0.0,
    refund_id VARCHAR(120),
    invoice_number VARCHAR(64),
    invoice_url VARCHAR(500),
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Add missing columns to backend.charging_sessions if they don't exist
-- (This handles the case where the table was created earlier but missing columns)
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS connector_no INTEGER;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS ocpp_transaction_id INTEGER;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS limit_type VARCHAR(32);
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS limit_value DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS meter_start BIGINT;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS meter_stop BIGINT;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS base_amount DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS gst_amount DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS total_amount DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS platform_fee DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS owner_revenue DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS connector_verified BOOLEAN DEFAULT FALSE;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS connector_verified_at TIMESTAMP;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS preauth_id VARCHAR(120);
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS preauth_amount DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS refund_amount DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS refund_id VARCHAR(120);
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(64);
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS invoice_url VARCHAR(500);

-- Create indexes on backend.charging_sessions
CREATE INDEX IF NOT EXISTS idx_backend_charging_sessions_station_id
    ON backend.charging_sessions(station_id);

CREATE INDEX IF NOT EXISTS idx_backend_charging_sessions_charger_id
    ON backend.charging_sessions(charger_id);

CREATE INDEX IF NOT EXISTS idx_backend_charging_sessions_status
    ON backend.charging_sessions(status);

CREATE TABLE IF NOT EXISTS backend.payment_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id BIGINT NOT NULL,
    pre_auth_id VARCHAR(120) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    operation VARCHAR(40) NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider_reference VARCHAR(150),
    provider_payload TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE backend.payment_records
    DROP CONSTRAINT IF EXISTS payment_records_session_id_fkey;

ALTER TABLE backend.payment_records
    DROP CONSTRAINT IF EXISTS backend_payment_records_session_id_fkey;

ALTER TABLE backend.payment_records
    ADD CONSTRAINT backend_payment_records_session_id_fkey
        FOREIGN KEY (session_id) REFERENCES backend.charging_sessions(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_payment_records_session_id
    ON backend.payment_records(session_id);

CREATE INDEX IF NOT EXISTS idx_payment_records_preauth_id
    ON backend.payment_records(pre_auth_id);
