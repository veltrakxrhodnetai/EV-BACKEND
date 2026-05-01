-- Insert Admin Users (corrected schema)
INSERT INTO backend.admin_users (username, password_hash, full_name, role, status, created_at) VALUES
('superadmin', '$2a$10$dummyhash1234567890123456789012345678901234567890123', 'Super Administrator', 'SUPER_ADMIN', 'ACTIVE', NOW()),
('admin_ops', '$2a$10$dummyhash1234567890123456789012345678901234567890123', 'Operations Admin', 'ADMIN', 'ACTIVE', NOW()),
('admin_view', '$2a$10$dummyhash1234567890123456789012345678901234567890123', 'View Only Admin', 'VIEWER', 'ACTIVE', NOW());

-- Insert Operator Accounts (corrected schema)
INSERT INTO backend.operator_accounts (name, mobile_number, pin_or_password_hash, permissions_json, status, created_at) VALUES
('Chennai Fleet Operator', '9876543210', '$2a$10$dummyhash1234567890123456789012345678901234567890123', '{"manage_stations": true, "view_sessions": true, "start_remote_charging": true}', 'ACTIVE', NOW()),
('Bangalore Operations', '9876543211', '$2a$10$dummyhash1234567890123456789012345678901234567890123', '{"manage_stations": true, "view_sessions": true}', 'ACTIVE', NOW());

-- Insert Operator Station Assignments (corrected schema - only operator_id and station_id)
INSERT INTO backend.operator_station_assignments (operator_id, station_id) VALUES
(1, 1), -- Chennai Fleet Operator -> Anna Nagar Chennai
(1, 2), -- Chennai Fleet Operator -> OMR Chennai
(2, 3); -- Bangalore Operations -> HSR Bangalore

-- Insert RFID Registry (corrected schema - rfid_uid instead of tag_id)
INSERT INTO backend.rfid_registry (rfid_uid, linked_user_id, fleet_name, status, issued_date, created_at) VALUES
('RFID-FLEET-001', NULL, 'TechCorp Fleet', 'ACTIVE', '2024-01-15', NOW()),
('RFID-FLEET-002', NULL, 'TechCorp Fleet', 'ACTIVE', '2024-01-15', NOW()),
('RFID-FLEET-003', NULL, 'LogiMove Fleet', 'ACTIVE', '2024-02-01', NOW()),
('RFID-PERSONAL-001', NULL, 'Individual User', 'ACTIVE', '2024-03-10', NOW()),
('RFID-PERSONAL-002', NULL, 'Individual User', 'SUSPENDED', '2024-03-20', NOW());

-- Insert OCPP Configurations (corrected schema - charge_point_identity instead of charger_id)
INSERT INTO backend.ocpp_configurations (charge_point_identity, websocket_url, heartbeat_interval_seconds, meter_value_interval_seconds, allowed_ips, security_mode, token_value, active, created_at) VALUES
('CP-CHENNAI-AN-01', 'ws://localhost:8080/ocpp/CP-CHENNAI-AN-01', 60, 30, '192.168.1.0/24', 'TOKEN', 'tk_chennai_an_01_secret', true, NOW()),
('CP-CHENNAI-AN-02', 'ws://localhost:8080/ocpp/CP-CHENNAI-AN-02', 60, 30, '192.168.1.0/24', 'TOKEN', 'tk_chennai_an_02_secret', true, NOW()),
('CP-CHENNAI-OMR-01', 'ws://localhost:8080/ocpp/CP-CHENNAI-OMR-01', 60, 30, '192.168.2.0/24', 'TOKEN', 'tk_chennai_omr_01_secret', true, NOW()),
('CP-CHENNAI-OMR-02', 'ws://localhost:8080/ocpp/CP-CHENNAI-OMR-02', 60, 30, '192.168.2.0/24', 'TOKEN', 'tk_chennai_omr_02_secret', true, NOW()),
('CP-BLORE-HSR-01', 'ws://localhost:8080/ocpp/CP-BLORE-HSR-01', 60, 30, '192.168.3.0/24', 'TOKEN', 'tk_blore_hsr_01_secret', true, NOW()),
('CP-BLORE-HSR-02', 'ws://localhost:8080/ocpp/CP-BLORE-HSR-02', 60, 30, '192.168.3.0/24', 'TOKEN', 'tk_blore_hsr_02_secret', true, NOW()),
('CP-MUMBAI-AND-01', 'ws://localhost:8080/ocpp/CP-MUMBAI-AND-01', 60, 30, '192.168.4.0/24', 'TOKEN', 'tk_mumbai_and_01_secret', true, NOW()),
('CP-MUMBAI-AND-02', 'ws://localhost:8080/ocpp/CP-MUMBAI-AND-02', 60, 30, '192.168.4.0/24', 'TOKEN', 'tk_mumbai_and_02_secret', true, NOW());

-- Verification Queries
SELECT 'Admin Users:' as table_name, COUNT(*) as count FROM backend.admin_users
UNION ALL
SELECT 'Operators:', COUNT(*) FROM backend.operator_accounts
UNION ALL
SELECT 'Operator Assignments:', COUNT(*) FROM backend.operator_station_assignments
UNION ALL
SELECT 'RFID Registry:', COUNT(*) FROM backend.rfid_registry
UNION ALL
SELECT 'OCPP Configurations:', COUNT(*) FROM backend.ocpp_configurations;
