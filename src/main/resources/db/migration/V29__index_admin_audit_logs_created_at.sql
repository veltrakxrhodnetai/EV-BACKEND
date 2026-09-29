CREATE INDEX IF NOT EXISTS idx_admin_audit_logs_created_at
    ON backend.admin_audit_logs (created_at DESC);
