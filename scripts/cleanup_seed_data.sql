-- Optional post-migration cleanup for local/dev clones.
-- This script removes known demo/test seed rows inserted by historical migrations.
-- It is intentionally NOT a Flyway migration, to avoid changing migration history.

BEGIN;

-- Remove demo live charger rows from legacy table.
DELETE FROM public.chargers_live
WHERE ocpp_identity IN ('BELECTRIQ-001', 'BELECTRIQ-002');

-- Remove demo operator assignments/accounts created in V10.
DELETE FROM backend.operator_station_assignments osa
USING backend.operator_accounts oa
WHERE osa.operator_id = oa.id
  AND oa.mobile_number IN ('9876543210', '9876543211', '9876543212', '9876543213');

DELETE FROM backend.operator_accounts
WHERE mobile_number IN ('9876543210', '9876543211', '9876543212', '9876543213');

-- Remove demo stations/chargers/connectors/tariffs created in V8 only when there are no sessions.
-- This protects environments where these stations were actually used.
DO $$
DECLARE
    anna_station_id BIGINT;
    omr_station_id BIGINT;
    has_sessions BOOLEAN;
BEGIN
    SELECT id INTO anna_station_id FROM backend.stations WHERE name = 'Veltrak EV Hub - Anna Nagar' LIMIT 1;
    SELECT id INTO omr_station_id  FROM backend.stations WHERE name = 'Veltrak EV Hub - OMR' LIMIT 1;

    IF anna_station_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM backend.charging_sessions cs
            WHERE cs.station_id = anna_station_id
        ) INTO has_sessions;

        IF NOT has_sessions THEN
            DELETE FROM backend.tariffs WHERE station_id = anna_station_id;
            DELETE FROM backend.connectors c
            USING backend.chargers ch
            WHERE c.charger_id = ch.id AND ch.station_id = anna_station_id;
            DELETE FROM backend.chargers WHERE station_id = anna_station_id;
            DELETE FROM backend.stations WHERE id = anna_station_id;
        END IF;
    END IF;

    IF omr_station_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM backend.charging_sessions cs
            WHERE cs.station_id = omr_station_id
        ) INTO has_sessions;

        IF NOT has_sessions THEN
            DELETE FROM backend.tariffs WHERE station_id = omr_station_id;
            DELETE FROM backend.connectors c
            USING backend.chargers ch
            WHERE c.charger_id = ch.id AND ch.station_id = omr_station_id;
            DELETE FROM backend.chargers WHERE station_id = omr_station_id;
            DELETE FROM backend.stations WHERE id = omr_station_id;
        END IF;
    END IF;
END $$;

COMMIT;
