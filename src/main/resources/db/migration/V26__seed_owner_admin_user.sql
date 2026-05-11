-- Seed owner admin user (idempotent)
-- Password hash is SHA-256 for "Admin@123".

INSERT INTO backend.admin_users (username, password_hash, full_name, role, status)
SELECT 'owneradmin',
       'e86f78a8a3caf0b60d8e74e5942aa6d86dc150cd3c03338aef25b7d2d7e3acc7',
       'Owner Admin',
       'ADMIN',
       'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1
    FROM backend.admin_users
    WHERE username = 'owneradmin'
);
