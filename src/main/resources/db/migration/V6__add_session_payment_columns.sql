ALTER TABLE charging_sessions
    ADD COLUMN IF NOT EXISTS amount_charged NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS payment_mode VARCHAR(40),
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(40);
