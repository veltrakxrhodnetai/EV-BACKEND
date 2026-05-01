-- Add role column to operator_station_assignments
ALTER TABLE backend.operator_station_assignments
    ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'OPERATOR';

-- Create operator_roles table for permission management
CREATE TABLE IF NOT EXISTS backend.operator_roles (
    id BIGSERIAL PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    permissions_json TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Insert default operator roles
INSERT INTO backend.operator_roles (role_name, description, permissions_json, active)
VALUES
    (
        'OPERATOR',
        'Station operator - can start/stop sessions, view reports',
        '{"start_session": true, "stop_session": true, "view_sessions": true, "view_reports": true, "view_billing": true}',
        TRUE
    ),
    (
        'TECHNICIAN',
        'Charger technician - can diagnose and maintain chargers',
        '{"start_session": false, "stop_session": false, "view_sessions": true, "diagnose_charger": true, "reset_charger": true, "view_maintenance_logs": true}',
        TRUE
    ),
    (
        'SUPERVISOR',
        'Station supervisor - can manage multiple stations and operators',
        '{"start_session": true, "stop_session": true, "view_sessions": true, "view_reports": true, "view_billing": true, "manage_operators": true, "view_maintenance_logs": true}',
        TRUE
    )
ON CONFLICT (role_name) DO NOTHING;

-- Seed test operators with station assignments
-- First, get the station IDs from the database and assign operators to them
WITH stations AS (
    SELECT id FROM backend.stations ORDER BY id LIMIT 3
),
operators_to_create AS (
    INSERT INTO backend.operator_accounts (name, mobile_number, pin_or_password_hash, status)
    SELECT 'Operator 1 - Anna Nagar', '9876543210', 'e86f78a8a3caf0b60d8e74e5942aa6d86dc150cd3c03338aef25b7d2d7e3acc7', 'ACTIVE'
    WHERE NOT EXISTS (SELECT 1 FROM backend.operator_accounts WHERE mobile_number = '9876543210')
    UNION ALL
    SELECT 'Operator 2 - OMR', '9876543211', 'e86f78a8a3caf0b60d8e74e5942aa6d86dc150cd3c03338aef25b7d2d7e3acc7', 'ACTIVE'
    WHERE NOT EXISTS (SELECT 1 FROM backend.operator_accounts WHERE mobile_number = '9876543211')
    UNION ALL
    SELECT 'Technician - Anna Nagar', '9876543212', 'e86f78a8a3caf0b60d8e74e5942aa6d86dc150cd3c03338aef25b7d2d7e3acc7', 'ACTIVE'
    WHERE NOT EXISTS (SELECT 1 FROM backend.operator_accounts WHERE mobile_number = '9876543212')
    UNION ALL
    SELECT 'Supervisor - All Stations', '9876543213', 'e86f78a8a3caf0b60d8e74e5942aa6d86dc150cd3c03338aef25b7d2d7e3acc7', 'ACTIVE'
    WHERE NOT EXISTS (SELECT 1 FROM backend.operator_accounts WHERE mobile_number = '9876543213')
    RETURNING id, mobile_number
)
INSERT INTO backend.operator_station_assignments (operator_id, station_id, role)
SELECT oa.id, s.id, 
    CASE 
        WHEN oa.mobile_number = '9876543210' THEN 'OPERATOR'       -- Operator for first station
        WHEN oa.mobile_number = '9876543211' THEN 'OPERATOR'       -- Operator for second station
        WHEN oa.mobile_number = '9876543212' THEN 'TECHNICIAN'     -- Technician for first station
        WHEN oa.mobile_number = '9876543213' THEN 'SUPERVISOR'     -- Supervisor for all
    END as role
FROM backend.operator_accounts oa, backend.stations s
WHERE 
    (oa.mobile_number = '9876543210' AND s.id = (SELECT MIN(id) FROM stations))  -- Op1 → Station1
    OR (oa.mobile_number = '9876543211' AND s.id = (SELECT id FROM stations WHERE id != (SELECT MIN(id) FROM stations) LIMIT 1))  -- Op2 → Station2
    OR (oa.mobile_number = '9876543212' AND s.id = (SELECT MIN(id) FROM stations))  -- Tech → Station1
    OR (oa.mobile_number = '9876543213')  -- Supervisor → All
ON CONFLICT (operator_id, station_id) DO NOTHING;

-- Note: Password hash above is SHA256('operator123')
-- Passwords for testing:
-- Operator 1: mobile_number='9876543210', PIN='123456'
-- Operator 2: mobile_number='9876543211', PIN='123456'
-- Technician: mobile_number='9876543212', PIN='123456'
-- Supervisor: mobile_number='9876543213', PIN='123456'
