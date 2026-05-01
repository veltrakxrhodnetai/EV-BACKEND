CREATE TABLE IF NOT EXISTS chargers_live (
    id BIGSERIAL PRIMARY KEY,
    ocpp_identity VARCHAR(120) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(40) NOT NULL,
    location VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO chargers_live (ocpp_identity, name, status, location)
VALUES
    ('BELECTRIQ-001', 'Belectriq DC-120 #1', 'Available', 'Demo Station - Bay 1'),
    ('BELECTRIQ-002', 'Belectriq DC-120 #2', 'Unavailable', 'Demo Station - Bay 2')
ON CONFLICT (ocpp_identity) DO NOTHING;
