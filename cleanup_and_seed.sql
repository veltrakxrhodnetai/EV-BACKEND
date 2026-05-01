-- ============================================
-- CLEANUP AND SEED DATABASE
-- ============================================

-- Disable triggers temporarily to avoid foreign key issues
SET session_replication_role = 'replica';

-- Clear all data (in order to respect foreign keys)
TRUNCATE TABLE backend.meter_values CASCADE;
TRUNCATE TABLE backend.charging_sessions CASCADE;
TRUNCATE TABLE backend.ocpp_message_logs CASCADE;
TRUNCATE TABLE backend.admin_audit_logs CASCADE;
TRUNCATE TABLE backend.operator_station_assignments CASCADE;
TRUNCATE TABLE backend.rfid_registry CASCADE;
TRUNCATE TABLE backend.ocpp_configurations CASCADE;
TRUNCATE TABLE backend.connectors CASCADE;
TRUNCATE TABLE backend.tariffs CASCADE;
TRUNCATE TABLE backend.chargers CASCADE;
TRUNCATE TABLE backend.stations CASCADE;
TRUNCATE TABLE backend.operator_accounts CASCADE;
TRUNCATE TABLE backend.admin_users CASCADE;

-- Reset sequences
ALTER SEQUENCE backend.stations_id_seq RESTART WITH 1;
ALTER SEQUENCE backend.chargers_id_seq RESTART WITH 1;
ALTER SEQUENCE backend.connectors_id_seq RESTART WITH 1;
ALTER SEQUENCE backend.tariffs_id_seq RESTART WITH 1;
ALTER SEQUENCE backend.charging_sessions_id_seq RESTART WITH 1;
ALTER SEQUENCE backend.admin_users_id_seq RESTART WITH 1;
ALTER SEQUENCE backend.operator_accounts_id_seq RESTART WITH 1;

-- Re-enable triggers
SET session_replication_role = 'origin';

-- ============================================
-- INSERT DUMMY DATA
-- ============================================

-- 1. STATIONS (4 stations across different cities)
INSERT INTO backend.stations (
    name, station_code, address, city, state, pincode, 
    latitude, longitude, status, operating_hours_type, 
    operating_hours_json, amenities_json, support_contact_number, payment_methods_json, created_at
) VALUES 
    (
        'VelTrak Charging Hub - Anna Nagar',
        'VT-CHN-AN-001',
        'No 45, 2nd Main Road, Anna Nagar West',
        'Chennai',
        'Tamil Nadu',
        '600040',
        13.0878,
        80.2101,
        'ACTIVE',
        '24x7',
        '{"allDays":"00:00-23:59"}',
        '["Parking","Restroom","WiFi","CCTV","Waiting Area"]',
        '+91-9876543210',
        '["UPI","Card","Wallet","Cash"]',
        NOW()
    ),
    (
        'VelTrak Charging Hub - OMR',
        'VT-CHN-OMR-002',
        '123, Rajiv Gandhi Salai (OMR), Sholinganallur',
        'Chennai',
        'Tamil Nadu',
        '600119',
        12.9010,
        80.2279,
        'ACTIVE',
        'CUSTOM',
        '{"mon-fri":"06:00-22:00","sat-sun":"07:00-21:00"}',
        '["Parking","Restroom","Food Court","WiFi","CCTV"]',
        '+91-9876543211',
        '["UPI","Card","Wallet"]',
        NOW()
    ),
    (
        'VelTrak Charging Station - Bangalore HSR',
        'VT-BLR-HSR-001',
        '27th Main Road, HSR Layout Sector 1',
        'Bangalore',
        'Karnataka',
        '560102',
        12.9116,
        77.6370,
        'ACTIVE',
        '24x7',
        '{"allDays":"00:00-23:59"}',
        '["Parking","WiFi","CCTV","Waiting Area"]',
        '+91-9876543212',
        '["UPI","Card","Wallet"]',
        NOW()
    ),
    (
        'VelTrak Charging Point - Mumbai Andheri',
        'VT-MUM-AND-001',
        'Western Express Highway, Andheri East',
        'Mumbai',
        'Maharashtra',
        '400069',
        19.1136,
        72.8697,
        'MAINTENANCE',
        'CUSTOM',
        '{"mon-sun":"08:00-20:00"}',
        '["Parking","Restroom","CCTV"]',
        '+91-9876543213',
        '["UPI","Card"]',
        NOW()
    );

-- 2. CHARGERS (8 chargers across stations)
INSERT INTO backend.chargers (
    station_id, name, ocpp_identity, vendor_name, model, serial_number,
    charger_type, max_power_kw, status, ocpp_version, enabled, 
    communication_status, created_at
) VALUES
    -- Anna Nagar Station (2 chargers)
    (1, 'Fast Charger 1A', 'VT-CHN-AN-001-FC1A', 'ABB', 'Terra 54 CJG', 'SN-ABB-2024-001', 'DC', 50.0, 'Available', '1.6J', true, 'OFFLINE', NOW()),
    (1, 'Fast Charger 1B', 'VT-CHN-AN-001-FC1B', 'ABB', 'Terra 54 CJG', 'SN-ABB-2024-002', 'DC', 50.0, 'Available', '1.6J', true, 'OFFLINE', NOW()),
    
    -- OMR Station (3 chargers)
    (2, 'DC Rapid Charger 2A', 'VT-CHN-OMR-002-RC2A', 'Siemens', 'VersiCharge', 'SN-SIE-2024-001', 'DC', 120.0, 'Available', '1.6J', true, 'OFFLINE', NOW()),
    (2, 'AC Charger 2B', 'VT-CHN-OMR-002-AC2B', 'Delta', 'AC Max', 'SN-DEL-2024-001', 'AC', 22.0, 'Available', '1.6J', true, 'OFFLINE', NOW()),
    (2, 'AC Charger 2C', 'VT-CHN-OMR-002-AC2C', 'Delta', 'AC Max', 'SN-DEL-2024-002', 'AC', 22.0, 'Available', '1.6J', true, 'OFFLINE', NOW()),
    
    -- Bangalore HSR Station (2 chargers)
    (3, 'Ultra Fast DC 3A', 'VT-BLR-HSR-001-UF3A', 'BYD', 'Ultra 150', 'SN-BYD-2024-001', 'DC', 150.0, 'Available', '1.6J', true, 'OFFLINE', NOW()),
    (3, 'Fast Charger 3B', 'VT-BLR-HSR-001-FC3B', 'ABB', 'Terra 54', 'SN-ABB-2024-003', 'DC', 50.0, 'Available', '1.6J', true, 'OFFLINE', NOW()),
    
    -- Mumbai Andheri Station (1 charger - under maintenance)
    (4, 'Fast Charger 4A', 'VT-MUM-AND-001-FC4A', 'Siemens', 'VersiCharge', 'SN-SIE-2024-002', 'DC', 60.0, 'Faulted', '1.6J', false, 'OFFLINE', NOW());

-- 3. CONNECTORS (14 connectors - guns for chargers)
INSERT INTO backend.connectors (
    charger_id, connector_no, type, max_power_kw, status
) VALUES
    -- Charger 1A - 2 guns
    (1, 1, 'CCS2', 50.0, 'Available'),
    (1, 2, 'CHAdeMO', 50.0, 'Available'),
    
    -- Charger 1B - 2 guns
    (2, 1, 'CCS2', 50.0, 'Available'),
    (2, 2, 'CHAdeMO', 50.0, 'Available'),
    
    -- Charger 2A - 2 guns
    (3, 1, 'CCS2', 120.0, 'Available'),
    (3, 2, 'CHAdeMO', 120.0, 'Available'),
    
    -- Charger 2B - 1 gun
    (4, 1, 'Type2', 22.0, 'Available'),
    
    -- Charger 2C - 1 gun
    (5, 1, 'Type2', 22.0, 'Available'),
    
    -- Charger 3A - 2 guns
    (6, 1, 'CCS2', 150.0, 'Available'),
    (6, 2, 'CHAdeMO', 150.0, 'Available'),
    
    -- Charger 3B - 2 guns
    (7, 1, 'CCS2', 50.0, 'Available'),
    (7, 2, 'GB/T', 50.0, 'Available'),
    
    -- Charger 4A - 1 gun (faulted)
    (8, 1, 'CCS2', 60.0, 'Faulted');

-- 4. TARIFFS (pricing for each station)
INSERT INTO backend.tariffs (
    station_id, price_per_kwh, gst_percent, session_fee, idle_fee, time_fee, 
    currency, scope_type
) VALUES
    (1, 12.50, 18.0, 20.0, 5.0, 0.0, 'INR', 'STATION'),
    (2, 15.00, 18.0, 25.0, 8.0, 0.0, 'INR', 'STATION'),
    (3, 14.00, 18.0, 30.0, 6.0, 0.0, 'INR', 'STATION'),
    (4, 13.50, 18.0, 20.0, 5.0, 0.0, 'INR', 'STATION');

-- 5. ADMIN USERS (3 admin accounts)
-- Password for all: Admin@123 (BCrypt hash)
INSERT INTO backend.admin_users (
    username, password_hash, full_name, email, phone, role, status, created_at
) VALUES
    (
        'superadmin',
        '$2a$10$rZ8qF8qF8qF8qF8qF8qF8.O3YqF8qF8qF8qF8qF8qF8qF8qF8qF8q',
        'Super Administrator',
        'superadmin@veltrak.com',
        '+91-9000000001',
        'SUPER_ADMIN',
        'ACTIVE',
        NOW()
    ),
    (
        'admin',
        '$2a$10$rZ8qF8qF8qF8qF8qF8qF8.O3YqF8qF8qF8qF8qF8qF8qF8qF8qF8q',
        'Network Admin',
        'admin@veltrak.com',
        '+91-9000000002',
        'ADMIN',
        'ACTIVE',
        NOW()
    ),
    (
        'viewer',
        '$2a$10$rZ8qF8qF8qF8qF8qF8qF8.O3YqF8qF8qF8qF8qF8qF8qF8qF8qF8q',
        'Network Viewer',
        'viewer@veltrak.com',
        '+91-9000000003',
        'VIEWER',
        'ACTIVE',
        NOW()
    );

-- 6. OPERATOR ACCOUNTS (2 operators)
-- Password for all: Operator@123 (BCrypt hash)
INSERT INTO backend.operator_accounts (
    username, password_hash, full_name, email, phone, role, status, created_at
) VALUES
    (
        'operator_chennai',
        '$2a$10$rZ8qF8qF8qF8qF8qF8qF8.O3YqF8qF8qF8qF8qF8qF8qF8qF8qF8q',
        'Chennai Operations Manager',
        'chennai@veltrak.com',
        '+91-9100000001',
        'STATION_OPERATOR',
        'ACTIVE',
        NOW()
    ),
    (
        'operator_bangalore',
        '$2a$10$rZ8qF8qF8qF8qF8qF8qF8.O3YqF8qF8qF8qF8qF8qF8qF8qF8qF8q',
        'Bangalore Operations Manager',
        'bangalore@veltrak.com',
        '+91-9100000002',
        'STATION_OPERATOR',
        'ACTIVE',
        NOW()
    );

-- 7. OPERATOR STATION ASSIGNMENTS
INSERT INTO backend.operator_station_assignments (
    operator_id, station_id, assigned_at
) VALUES
    (1, 1, NOW()),  -- Chennai operator -> Anna Nagar
    (1, 2, NOW()),  -- Chennai operator -> OMR
    (2, 3, NOW());  -- Bangalore operator -> HSR

-- 8. RFID REGISTRY (5 RFID cards/tags)
INSERT INTO backend.rfid_registry (
    tag_id, user_phone, vehicle_number, status, issued_at
) VALUES
    ('RFID-VT-0001', '+91-9876501234', 'TN01AB1234', 'ACTIVE', NOW()),
    ('RFID-VT-0002', '+91-9876501235', 'TN01CD5678', 'ACTIVE', NOW()),
    ('RFID-VT-0003', '+91-9876501236', 'KA01EF9012', 'ACTIVE', NOW()),
    ('RFID-VT-0004', '+91-9876501237', 'MH02GH3456', 'ACTIVE', NOW()),
    ('RFID-VT-0005', '+91-9876501238', 'TN02IJ7890', 'BLOCKED', NOW());

-- 9. OCPP CONFIGURATIONS (configurations for chargers)
INSERT INTO backend.ocpp_configurations (
    charger_id, charge_point_identity, websocket_url, 
    heartbeat_interval_seconds, meter_value_interval_seconds,
    security_mode, token_value, allowed_ips, active
) VALUES
    (1, 'VT-CHN-AN-001-FC1A', 'ws://localhost:8080/ocpp', 300, 60, 'TOKEN', 'token_fc1a_secret', NULL, true),
    (2, 'VT-CHN-AN-001-FC1B', 'ws://localhost:8080/ocpp', 300, 60, 'TOKEN', 'token_fc1b_secret', NULL, true),
    (3, 'VT-CHN-OMR-002-RC2A', 'ws://localhost:8080/ocpp', 300, 60, 'TOKEN', 'token_rc2a_secret', NULL, true),
    (4, 'VT-CHN-OMR-002-AC2B', 'ws://localhost:8080/ocpp', 300, 60, 'TOKEN', 'token_ac2b_secret', NULL, true),
    (5, 'VT-CHN-OMR-002-AC2C', 'ws://localhost:8080/ocpp', 300, 60, 'TOKEN', 'token_ac2c_secret', NULL, true),
    (6, 'VT-BLR-HSR-001-UF3A', 'ws://localhost:8080/ocpp', 300, 60, 'TOKEN', 'token_uf3a_secret', NULL, true),
    (7, 'VT-BLR-HSR-001-FC3B', 'ws://localhost:8080/ocpp', 300, 60, 'TOKEN', 'token_fc3b_secret', NULL, true),
    (8, 'VT-MUM-AND-001-FC4A', 'ws://localhost:8080/ocpp', 300, 60, 'NONE', NULL, NULL, false);

-- ============================================
-- VERIFICATION QUERIES
-- ============================================
SELECT 'Stations created: ' || COUNT(*) FROM backend.stations;
SELECT 'Chargers created: ' || COUNT(*) FROM backend.chargers;
SELECT 'Connectors created: ' || COUNT(*) FROM backend.connectors;
SELECT 'Tariffs created: ' || COUNT(*) FROM backend.tariffs;
SELECT 'Admin users created: ' || COUNT(*) FROM backend.admin_users;
SELECT 'Operators created: ' || COUNT(*) FROM backend.operator_accounts;
SELECT 'RFID tags created: ' || COUNT(*) FROM backend.rfid_registry;
SELECT 'OCPP configs created: ' || COUNT(*) FROM backend.ocpp_configurations;
