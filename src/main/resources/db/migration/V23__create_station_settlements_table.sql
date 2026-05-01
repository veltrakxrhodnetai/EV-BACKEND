CREATE TABLE IF NOT EXISTS backend.station_settlements (
    id BIGSERIAL PRIMARY KEY,
    station_id BIGINT NOT NULL UNIQUE REFERENCES backend.stations(id) ON DELETE CASCADE,
    owner_id BIGINT,
    total_revenue DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    settled_amount DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    pending_amount DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_station_settlements_owner_id
    ON backend.station_settlements (owner_id);