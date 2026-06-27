CREATE TABLE IF NOT EXISTS backend.charger_status_logs (
    id               BIGSERIAL PRIMARY KEY,
    charger_id       BIGINT,
    charger_name     VARCHAR(255),
    ocpp_identity    VARCHAR(255),
    station_id       BIGINT,
    status           VARCHAR(50)  NOT NULL,
    started_at       TIMESTAMP    NOT NULL,
    ended_at         TIMESTAMP,
    duration_seconds BIGINT,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_charger_status_logs_charger_id   ON backend.charger_status_logs (charger_id);
CREATE INDEX idx_charger_status_logs_ocpp_identity ON backend.charger_status_logs (ocpp_identity);
CREATE INDEX idx_charger_status_logs_started_at    ON backend.charger_status_logs (started_at);
CREATE INDEX idx_charger_status_logs_open          ON backend.charger_status_logs (ocpp_identity, started_at DESC) WHERE ended_at IS NULL;
