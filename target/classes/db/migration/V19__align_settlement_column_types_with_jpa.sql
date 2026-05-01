ALTER TABLE backend.settlements
    ALTER COLUMN total_revenue TYPE DOUBLE PRECISION USING total_revenue::double precision,
    ALTER COLUMN settled_amount TYPE DOUBLE PRECISION USING settled_amount::double precision,
    ALTER COLUMN pending_amount TYPE DOUBLE PRECISION USING pending_amount::double precision;
