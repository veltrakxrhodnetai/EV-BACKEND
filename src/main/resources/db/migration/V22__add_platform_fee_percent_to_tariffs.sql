ALTER TABLE backend.tariffs
    ADD COLUMN IF NOT EXISTS platform_fee_percent DOUBLE PRECISION NOT NULL DEFAULT 12.0;

UPDATE backend.tariffs
SET platform_fee_percent = 12.0
WHERE platform_fee_percent IS NULL;
