CREATE TABLE IF NOT EXISTS backend.settlements (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL UNIQUE,
    total_revenue NUMERIC(12, 2) NOT NULL DEFAULT 0,
    settled_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    pending_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_settlements_owner_id
    ON backend.settlements (owner_id);
