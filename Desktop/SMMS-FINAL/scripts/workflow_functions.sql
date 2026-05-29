-- ══════════════════════════════════════════════════════
--  SLA Breach Checker (run via pg_cron or backend scheduler)
-- ══════════════════════════════════════════════════════

-- Mark breached SLAs
UPDATE work_orders
SET sla_breached = TRUE
WHERE sla_deadline < NOW()
  AND sla_breached = FALSE
  AND status NOT IN ('completed', 'closed', 'cancelled');

-- ══════════════════════════════════════════════════════
--  Workflow Transition Validation Function
-- ══════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION validate_status_transition(
  p_from ticket_status,
  p_to   ticket_status,
  p_role user_role
) RETURNS BOOLEAN AS $$
BEGIN
  -- Valid transitions matrix
  RETURN CASE
    WHEN p_from = 'new'               AND p_to = 'under_review'      AND p_role IN ('control_room') THEN TRUE
    WHEN p_from = 'under_review'      AND p_to = 'assigned'          AND p_role IN ('control_room') THEN TRUE
    WHEN p_from = 'assigned'          AND p_to = 'in_progress'       AND p_role IN ('technician')   THEN TRUE
    WHEN p_from = 'in_progress'       AND p_to = 'completed'         AND p_role IN ('technician')   THEN TRUE
    WHEN p_from = 'in_progress'       AND p_to = 'waiting_materials' AND p_role IN ('technician')   THEN TRUE
    WHEN p_from = 'waiting_materials' AND p_to = 'waiting_approval'  AND p_role IN ('warehouse')    THEN TRUE
    WHEN p_from = 'waiting_materials' AND p_to = 'in_progress'       AND p_role IN ('warehouse')    THEN TRUE  -- materials provided
    WHEN p_from = 'waiting_approval'  AND p_to = 'in_progress'       AND p_role IN ('operating_company_manager') THEN TRUE  -- approved
    WHEN p_from = 'waiting_approval'  AND p_to = 'waiting_materials' AND p_role IN ('operating_company_manager') THEN TRUE  -- rejected
    WHEN p_from = 'completed'         AND p_to = 'closed'            AND p_role IN ('control_room','maintenance_manager') THEN TRUE
    WHEN p_from = 'completed'         AND p_to = 'in_progress'       AND p_role IN ('control_room') THEN TRUE  -- reopen
    -- Cancel allowed from multiple states
    WHEN p_to = 'cancelled' AND p_role IN ('control_room','maintenance_manager','hospital_supervisor') THEN TRUE
    ELSE FALSE
  END;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- ══════════════════════════════════════════════════════
--  Auto-create control rooms for new center (trigger)
-- ══════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION auto_create_control_rooms()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO control_rooms (center_id, type, name, name_ar) VALUES
    (NEW.id, 'residential', 'Residential Maintenance', 'صيانة الإسكان'),
    (NEW.id, 'hospital',    'Hospital Maintenance',    'صيانة المستشفى'),
    (NEW.id, 'it',          'IT Maintenance',          'صيانة تقنية المعلومات'),
    (NEW.id, 'medical',     'Medical Maintenance',     'الصيانة الطبية');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_auto_control_rooms
  AFTER INSERT ON centers
  FOR EACH ROW EXECUTE FUNCTION auto_create_control_rooms();
