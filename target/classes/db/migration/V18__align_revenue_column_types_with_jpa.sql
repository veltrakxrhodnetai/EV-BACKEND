ALTER TABLE backend.charging_sessions
    ALTER COLUMN platform_fee TYPE DOUBLE PRECISION USING platform_fee::double precision,
    ALTER COLUMN owner_revenue TYPE DOUBLE PRECISION USING owner_revenue::double precision;
