-- Add columns for connector verification, payment tracking, and invoice management
ALTER TABLE charging_sessions
    ADD COLUMN IF NOT EXISTS connector_verified BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS connector_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS preauth_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS preauth_amount NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS refund_amount NUMERIC(12, 2) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS refund_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(50),
    ADD COLUMN IF NOT EXISTS invoice_url VARCHAR(500);

-- Create index on preauth_id for faster payment lookups
CREATE INDEX IF NOT EXISTS idx_charging_sessions_preauth_id 
    ON charging_sessions(preauth_id);

-- Add comment explaining the new workflow
COMMENT ON COLUMN charging_sessions.connector_verified IS 
    'TRUE when customer confirms connector is plugged into vehicle before payment capture';

COMMENT ON COLUMN charging_sessions.preauth_id IS 
    'Payment gateway pre-authorization ID (hold before charging starts)';

COMMENT ON COLUMN charging_sessions.refund_amount IS 
    'Amount refunded to customer for unused pre-authorized amount';

COMMENT ON COLUMN charging_sessions.invoice_number IS 
    'Unique invoice number for GST compliance (format: INV-YYYY-MM-NNNNNN)';
