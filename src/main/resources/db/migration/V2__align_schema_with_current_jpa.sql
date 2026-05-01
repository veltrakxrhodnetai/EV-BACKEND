ALTER TABLE charging_sessions
    DROP CONSTRAINT IF EXISTS charging_sessions_charger_id_fkey;

ALTER TABLE charging_sessions
    ALTER COLUMN charger_id TYPE VARCHAR(80) USING charger_id::text;

ALTER TABLE charging_sessions
    ADD COLUMN IF NOT EXISTS connector_number INTEGER,
    ADD COLUMN IF NOT EXISTS transaction_id VARCHAR(120),
    ADD COLUMN IF NOT EXISTS id_tag VARCHAR(80),
    ADD COLUMN IF NOT EXISTS stop_reason VARCHAR(120);

UPDATE charging_sessions
SET connector_number = 1
WHERE connector_number IS NULL;

ALTER TABLE charging_sessions
    ALTER COLUMN connector_number SET NOT NULL;

UPDATE charging_sessions
SET transaction_id = 'txn-' || replace(gen_random_uuid()::text, '-', '')
WHERE transaction_id IS NULL;

ALTER TABLE charging_sessions
    ALTER COLUMN transaction_id SET NOT NULL;

CREATE TABLE IF NOT EXISTS payment_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES charging_sessions(id) ON DELETE CASCADE,
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

CREATE INDEX IF NOT EXISTS idx_payment_records_session_id ON payment_records(session_id);
CREATE INDEX IF NOT EXISTS idx_payment_records_preauth_id ON payment_records(pre_auth_id);
