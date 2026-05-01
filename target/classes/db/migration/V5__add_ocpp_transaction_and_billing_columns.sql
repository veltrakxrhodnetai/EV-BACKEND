ALTER TABLE charging_sessions
    ADD COLUMN IF NOT EXISTS ocpp_transaction_id INTEGER,
    ADD COLUMN IF NOT EXISTS energy_consumed_kwh NUMERIC(12, 3),
    ADD COLUMN IF NOT EXISTS base_amount NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS gst_amount NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS total_amount NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS ended_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_charging_sessions_ocpp_transaction_id
    ON charging_sessions(ocpp_transaction_id);
