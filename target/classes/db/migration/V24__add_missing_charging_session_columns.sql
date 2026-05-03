-- Add missing columns to backend.charging_sessions table
-- These columns are required by the JPA entity but were missing from earlier migrations

ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS connector_no INTEGER;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS ocpp_transaction_id INTEGER;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS limit_type VARCHAR(32);
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS limit_value DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS meter_start BIGINT;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS meter_stop BIGINT;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS base_amount DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS gst_amount DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS total_amount DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS platform_fee DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS owner_revenue DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS connector_verified BOOLEAN DEFAULT FALSE;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS connector_verified_at TIMESTAMP;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS preauth_id VARCHAR(120);
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS preauth_amount DOUBLE PRECISION;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS refund_amount DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS refund_id VARCHAR(120);
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(64);
ALTER TABLE backend.charging_sessions ADD COLUMN IF NOT EXISTS invoice_url VARCHAR(500);

-- Update NOT NULL constraints for required fields
ALTER TABLE backend.charging_sessions ALTER COLUMN connector_no SET NOT NULL;
ALTER TABLE backend.charging_sessions ALTER COLUMN started_by SET NOT NULL;
ALTER TABLE backend.charging_sessions ALTER COLUMN payment_mode SET NOT NULL;
ALTER TABLE backend.charging_sessions ALTER COLUMN payment_status SET NOT NULL;