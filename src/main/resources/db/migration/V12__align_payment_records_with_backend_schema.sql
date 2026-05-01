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
