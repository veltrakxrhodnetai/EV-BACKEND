-- Create customer table for authentication
CREATE TABLE customer (
    id BIGSERIAL PRIMARY KEY,
    phone_number VARCHAR(15) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    passcode_hash VARCHAR(255),
    has_passcode BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on phone_number for faster lookups
CREATE INDEX idx_customer_phone_number ON customer(phone_number);

-- Comments for documentation
COMMENT ON TABLE customer IS 'Customer authentication and profile information';
COMMENT ON COLUMN customer.phone_number IS 'Unique phone number (10 digits, stored without country code)';
COMMENT ON COLUMN customer.passcode_hash IS 'SHA-256 hash of 4-digit passcode';
COMMENT ON COLUMN customer.has_passcode IS 'Flag to quickly check if user has set a passcode';
