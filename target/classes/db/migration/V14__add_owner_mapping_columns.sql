ALTER TABLE backend.stations
    ADD COLUMN IF NOT EXISTS owner_id BIGINT;

ALTER TABLE backend.chargers
    ADD COLUMN IF NOT EXISTS owner_id BIGINT;

ALTER TABLE backend.operator_accounts
    ADD COLUMN IF NOT EXISTS email VARCHAR(255);
