ALTER TABLE backend.charging_sessions
    ADD COLUMN IF NOT EXISTS station_id BIGINT,
    ADD COLUMN IF NOT EXISTS owner_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_charging_sessions_station_id
    ON backend.charging_sessions (station_id);

CREATE INDEX IF NOT EXISTS idx_charging_sessions_owner_id
    ON backend.charging_sessions (owner_id);

UPDATE backend.charging_sessions cs
SET station_id = c.station_id,
    owner_id = c.owner_id
FROM backend.chargers c
WHERE cs.charger_id = c.id
  AND (cs.station_id IS NULL OR cs.owner_id IS NULL);
