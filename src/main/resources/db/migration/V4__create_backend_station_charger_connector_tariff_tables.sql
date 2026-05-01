CREATE SCHEMA IF NOT EXISTS backend;

CREATE TABLE IF NOT EXISTS backend.stations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(120) NOT NULL,
    state VARCHAR(120) NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS backend.chargers (
    id BIGSERIAL PRIMARY KEY,
    station_id BIGINT NOT NULL REFERENCES backend.stations(id) ON DELETE CASCADE,
    ocpp_identity VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    max_power_kw DOUBLE PRECISION NOT NULL,
    status VARCHAR(40) NOT NULL,
    last_heartbeat TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS backend.connectors (
    id BIGSERIAL PRIMARY KEY,
    charger_id BIGINT NOT NULL REFERENCES backend.chargers(id) ON DELETE CASCADE,
    connector_no INTEGER NOT NULL,
    type VARCHAR(80) NOT NULL,
    max_power_kw DOUBLE PRECISION NOT NULL,
    status VARCHAR(40) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_backend_connector_charger_no UNIQUE (charger_id, connector_no)
);

CREATE TABLE IF NOT EXISTS backend.tariffs (
    id BIGSERIAL PRIMARY KEY,
    station_id BIGINT NOT NULL REFERENCES backend.stations(id) ON DELETE CASCADE,
    price_per_kwh DOUBLE PRECISION NOT NULL,
    gst_percent DOUBLE PRECISION NOT NULL DEFAULT 18.0,
    session_fee DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    currency VARCHAR(10) NOT NULL DEFAULT 'INR',
    CONSTRAINT uq_backend_tariff_station UNIQUE (station_id)
);
