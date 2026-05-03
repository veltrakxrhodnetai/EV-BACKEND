-- Create backend.meter_values table
-- This table stores meter readings for charging sessions

CREATE TABLE IF NOT EXISTS backend.meter_values (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES backend.charging_sessions(id) ON DELETE CASCADE,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
    energy_wh BIGINT NOT NULL,
    power_w DOUBLE PRECISION,
    voltage_v DOUBLE PRECISION,
    current_a DOUBLE PRECISION
);

-- Create indexes for better performance
CREATE INDEX IF NOT EXISTS idx_backend_meter_values_session_id
    ON backend.meter_values(session_id);

CREATE INDEX IF NOT EXISTS idx_backend_meter_values_timestamp
    ON backend.meter_values(timestamp);