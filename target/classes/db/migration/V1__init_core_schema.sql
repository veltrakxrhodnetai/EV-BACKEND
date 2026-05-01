CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mobile VARCHAR(20) NOT NULL UNIQUE,
    full_name VARCHAR(120),
    email VARCHAR(160),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE stations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    station_code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    address_line1 VARCHAR(255),
    city VARCHAR(100),
    state VARCHAR(100),
    country VARCHAR(100) NOT NULL DEFAULT 'India',
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE chargers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    station_id UUID NOT NULL REFERENCES stations(id) ON DELETE CASCADE,
    charger_id VARCHAR(80) NOT NULL UNIQUE,
    serial_number VARCHAR(100),
    model_name VARCHAR(120) NOT NULL,
    manufacturer VARCHAR(120) NOT NULL,
    power_kw NUMERIC(10, 2),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE connectors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    charger_id UUID NOT NULL REFERENCES chargers(id) ON DELETE CASCADE,
    connector_number INTEGER NOT NULL,
    connector_type VARCHAR(80),
    max_power_kw NUMERIC(10, 2),
    status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_connectors_charger_number UNIQUE (charger_id, connector_number)
);

CREATE TABLE rfids (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    rfid_uid VARCHAR(80) NOT NULL UNIQUE,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    blocked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE charging_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    operator_id UUID REFERENCES users(id) ON DELETE SET NULL,
    station_id UUID REFERENCES stations(id) ON DELETE SET NULL,
    charger_id UUID NOT NULL REFERENCES chargers(id) ON DELETE RESTRICT,
    connector_id UUID REFERENCES connectors(id) ON DELETE SET NULL,
    rfid_id UUID REFERENCES rfids(id) ON DELETE SET NULL,
    started_by VARCHAR(30) NOT NULL,
    vehicle_number VARCHAR(40),
    limit_type VARCHAR(30),
    limit_value NUMERIC(12, 2),
    meter_start NUMERIC(12, 3),
    meter_stop NUMERIC(12, 3),
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    pre_auth_id VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE meter_values (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES charging_sessions(id) ON DELETE CASCADE,
    charger_id UUID REFERENCES chargers(id) ON DELETE SET NULL,
    reading_timestamp TIMESTAMPTZ NOT NULL,
    energy_kwh NUMERIC(12, 3) NOT NULL,
    power_kw NUMERIC(12, 3),
    voltage NUMERIC(10, 3),
    current NUMERIC(10, 3),
    status VARCHAR(30) DEFAULT 'RECORDED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES charging_sessions(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    pre_auth_id VARCHAR(120),
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    event_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider_reference VARCHAR(150),
    provider_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chargers_status ON chargers(status);
CREATE INDEX idx_connectors_status ON connectors(status);
CREATE INDEX idx_rfids_status ON rfids(status);
CREATE INDEX idx_charging_sessions_charger_id ON charging_sessions(charger_id);
CREATE INDEX idx_charging_sessions_status ON charging_sessions(status);
CREATE INDEX idx_meter_values_session_id ON meter_values(session_id);
CREATE INDEX idx_meter_values_charger_id ON meter_values(charger_id);
CREATE INDEX idx_meter_values_status ON meter_values(status);
CREATE INDEX idx_payments_session_id ON payments(session_id);
CREATE INDEX idx_payments_status ON payments(status);

INSERT INTO stations (id, station_code, name, address_line1, city, state, country, latitude, longitude, status)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    'STN-BLR-001',
    'Belectriq Hub Bengaluru',
    'Electronic City Phase 1',
    'Bengaluru',
    'Karnataka',
    'India',
    12.8399888,
    77.6770320,
    'ACTIVE'
);

INSERT INTO chargers (id, station_id, charger_id, serial_number, model_name, manufacturer, power_kw, status)
VALUES (
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    'CHG-BLR-DC120-01',
    'BQDC120-SN-0001',
    'BQ-DC120',
    'Belectriq',
    120.00,
    'ACTIVE'
);
