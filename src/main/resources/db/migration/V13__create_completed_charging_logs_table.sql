CREATE TABLE IF NOT EXISTS backend.completed_charging_logs (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL UNIQUE,
    station_id BIGINT,
    station_name VARCHAR(255),
    charger_id BIGINT,
    charger_name VARCHAR(255),
    charger_ocpp_identity VARCHAR(255),
    connector_no INTEGER,
    vehicle_number VARCHAR(64),
    phone_number VARCHAR(32),
    started_by VARCHAR(32),
    payment_mode VARCHAR(32),
    payment_status VARCHAR(64),
    energy_consumed_kwh DOUBLE PRECISION,
    amount_paid DOUBLE PRECISION,
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    payment_completed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_completed_charging_logs_station_id
    ON backend.completed_charging_logs (station_id);

CREATE INDEX IF NOT EXISTS idx_completed_charging_logs_payment_completed_at
    ON backend.completed_charging_logs (payment_completed_at DESC);
