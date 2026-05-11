-- Optimize high-frequency live/session lookup paths
-- Safe to run repeatedly with IF NOT EXISTS guards.

-- Used by: existsByCharger_IdAndConnectorNoAndStatusIn(...)
CREATE INDEX IF NOT EXISTS idx_backend_charging_sessions_charger_connector_status
    ON backend.charging_sessions (charger_id, connector_no, status);

-- Used by: findByPhoneNumberAndStatusInOrderByCreatedAtDesc(...) and existsByPhoneNumber(...)
CREATE INDEX IF NOT EXISTS idx_backend_charging_sessions_phone_status_created_at
    ON backend.charging_sessions (phone_number, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_backend_charging_sessions_phone_number
    ON backend.charging_sessions (phone_number);

-- Used by: status-driven monitor and active/live reads
CREATE INDEX IF NOT EXISTS idx_backend_charging_sessions_status_created_at
    ON backend.charging_sessions (status, created_at DESC);

-- Used by: findFirstByOcppTransactionIdOrderByCreatedAtDesc(...)
CREATE INDEX IF NOT EXISTS idx_backend_charging_sessions_ocpp_tx_created_at
    ON backend.charging_sessions (ocpp_transaction_id, created_at DESC);

-- Used by: findLatestBySessionId() ordering by timestamp desc, id desc
CREATE INDEX IF NOT EXISTS idx_backend_meter_values_session_timestamp_id_desc
    ON backend.meter_values (session_id, timestamp DESC, id DESC);
