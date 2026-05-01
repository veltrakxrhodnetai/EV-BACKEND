ALTER TABLE backend.charging_sessions
    ADD COLUMN IF NOT EXISTS platform_fee NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS owner_revenue NUMERIC(12, 2);
