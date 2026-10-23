-- ══════════════════════════════════════════════════════
--  SMMS – Full Database Schema + Seed
-- ══════════════════════════════════════════════════════

-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── ENUMs ─────────────────────────────────────────────

CREATE TYPE user_role AS ENUM (
  'system_admin','region_supervisor','hospital_supervisor',
  'control_room','technician','maintenance_manager',
  'electrical_manager','warehouse','operating_company_manager',
  'resident','department'
);

CREATE TYPE ticket_status AS ENUM (
  'new','under_review','assigned','in_progress',
  'waiting_materials','waiting_approval','completed','closed','cancelled'
);

CREATE TYPE ticket_priority AS ENUM ('critical','high','medium','low');

CREATE TYPE control_room_type AS ENUM (
  'residential','hospital','it','medical'
);

CREATE TYPE sub_type AS ENUM (
  -- residential/hospital
  'electrical','plumbing','hvac','civil',
  -- IT
  'printer','network','software','devices',
  -- medical
  'equipment_failure','preventive_maintenance','calibration'
);

CREATE TYPE approval_decision AS ENUM ('approved','rejected','purchase_request');

-- ── REGIONS ───────────────────────────────────────────

CREATE TABLE regions (
  id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  name         VARCHAR(200) NOT NULL,
  name_ar      VARCHAR(200) NOT NULL,
  code         VARCHAR(20)  NOT NULL UNIQUE,
  logo_url     TEXT,
  is_active    BOOLEAN DEFAULT TRUE,
  created_at   TIMESTAMPTZ DEFAULT NOW(),
  deleted_at   TIMESTAMPTZ
);

-- ── CENTERS (HOSPITALS) ───────────────────────────────

CREATE TABLE centers (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  region_id       UUID NOT NULL REFERENCES regions(id),
  name            VARCHAR(200) NOT NULL,
  name_ar         VARCHAR(200) NOT NULL,
  code            VARCHAR(20)  NOT NULL UNIQUE,
  logo_url        TEXT,
  op_company_logo TEXT,
  address         TEXT,
  is_active       BOOLEAN DEFAULT TRUE,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  deleted_at      TIMESTAMPTZ
);

-- ── CONTROL ROOMS ─────────────────────────────────────

CREATE TABLE control_rooms (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  center_id   UUID NOT NULL REFERENCES centers(id),
  type        control_room_type NOT NULL,
  name        VARCHAR(200) NOT NULL,
  name_ar     VARCHAR(200) NOT NULL,
  is_active   BOOLEAN DEFAULT TRUE,
  created_at  TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE (center_id, type)
);

-- ── USERS ─────────────────────────────────────────────

CREATE TABLE users (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  region_id       UUID REFERENCES regions(id),
  center_id       UUID REFERENCES centers(id),
  username        VARCHAR(100) NOT NULL UNIQUE,
  email           VARCHAR(255) NOT NULL UNIQUE,
  password_hash   TEXT NOT NULL,
  full_name       VARCHAR(200) NOT NULL,
  full_name_ar    VARCHAR(200),
  phone           VARCHAR(20),
  role            user_role NOT NULL,
  is_active       BOOLEAN DEFAULT TRUE,
  last_login      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  deleted_at      TIMESTAMPTZ
);

-- ── USER ↔ CONTROL ROOM ACCESS ────────────────────────

CREATE TABLE user_control_room_access (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id         UUID NOT NULL REFERENCES users(id),
  control_room_id UUID NOT NULL REFERENCES control_rooms(id),
  granted_at      TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE(user_id, control_room_id)
);

-- ── WORK ORDERS ───────────────────────────────────────

CREATE TABLE work_orders (
  id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  ticket_number         VARCHAR(30) NOT NULL UNIQUE,
  center_id             UUID NOT NULL REFERENCES centers(id),
  control_room_id       UUID NOT NULL REFERENCES control_rooms(id),
  status                ticket_status NOT NULL DEFAULT 'new',
  current_stage         VARCHAR(50) NOT NULL DEFAULT 'new',
  priority              ticket_priority NOT NULL DEFAULT 'medium',
  sub_type              sub_type NOT NULL,
  title                 VARCHAR(500) NOT NULL,
  description           TEXT,
  location_detail       VARCHAR(300),

  -- Parties
  requested_by_id       UUID NOT NULL REFERENCES users(id),
  created_by_role       user_role NOT NULL,
  assigned_to_id        UUID REFERENCES users(id),
  transferred_to_id     UUID REFERENCES users(id),
  approved_by_id        UUID REFERENCES users(id),

  -- Materials
  needs_materials       BOOLEAN DEFAULT FALSE,
  warehouse_notes       TEXT,
  approval_decision     approval_decision,
  material_required_at  TIMESTAMPTZ,

  -- Reports
  tech_report           TEXT,
  rejection_reason      TEXT,

  -- SLA
  sla_deadline          TIMESTAMPTZ,
  sla_breached          BOOLEAN DEFAULT FALSE,

  -- Lifecycle
  scheduled_date        TIMESTAMPTZ,
  completed_date        TIMESTAMPTZ,
  closed_at             TIMESTAMPTZ,
  reopened_count        INTEGER DEFAULT 0,
  is_locked             BOOLEAN DEFAULT FALSE,
  locked_by_id          UUID REFERENCES users(id),
  locked_at             TIMESTAMPTZ,

  created_at            TIMESTAMPTZ DEFAULT NOW(),
  updated_at            TIMESTAMPTZ DEFAULT NOW(),
  deleted_at            TIMESTAMPTZ
);

-- ── TICKET HISTORY (AUDIT) ────────────────────────────

CREATE TABLE ticket_history (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  work_order_id   UUID NOT NULL REFERENCES work_orders(id),
  actor_id        UUID NOT NULL REFERENCES users(id),
  actor_role      user_role NOT NULL,
  from_status     ticket_status,
  to_status       ticket_status NOT NULL,
  stage           VARCHAR(50),
  note            TEXT,
  created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ── ATTACHMENTS ───────────────────────────────────────

CREATE TABLE attachments (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  work_order_id   UUID NOT NULL REFERENCES work_orders(id),
  uploaded_by_id  UUID NOT NULL REFERENCES users(id),
  file_name       VARCHAR(300) NOT NULL,
  file_path       TEXT NOT NULL,
  file_size       INTEGER,
  mime_type       VARCHAR(100),
  created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ── NOTIFICATIONS ─────────────────────────────────────

CREATE TABLE notifications (
  id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id         UUID NOT NULL REFERENCES users(id),
  work_order_id   UUID REFERENCES work_orders(id),
  type            VARCHAR(50) NOT NULL,
  title           VARCHAR(300) NOT NULL,
  body            TEXT,
  is_read         BOOLEAN DEFAULT FALSE,
  created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ── INDEXES ───────────────────────────────────────────

CREATE INDEX idx_work_orders_center     ON work_orders(center_id);
CREATE INDEX idx_work_orders_control    ON work_orders(control_room_id);
CREATE INDEX idx_work_orders_status     ON work_orders(status);
CREATE INDEX idx_work_orders_assigned   ON work_orders(assigned_to_id);
CREATE INDEX idx_work_orders_priority   ON work_orders(priority);
CREATE INDEX idx_work_orders_sla        ON work_orders(sla_deadline, sla_breached);
CREATE INDEX idx_ticket_history_order   ON ticket_history(work_order_id);
CREATE INDEX idx_notifications_user     ON notifications(user_id, is_read);
CREATE INDEX idx_users_role             ON users(role);

-- ── TRIGGER: updated_at ───────────────────────────────

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_work_orders_updated
  BEFORE UPDATE ON work_orders
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── TRIGGER: SLA deadline ─────────────────────────────

CREATE OR REPLACE FUNCTION set_sla_deadline()
RETURNS TRIGGER AS $$
DECLARE hours INT;
BEGIN
  CASE NEW.priority
    WHEN 'critical' THEN hours := 4;
    WHEN 'high'     THEN hours := 8;
    WHEN 'medium'   THEN hours := 24;
    ELSE                  hours := 48;
  END CASE;
  NEW.sla_deadline := NOW() + (hours || ' hours')::INTERVAL;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_set_sla
  BEFORE INSERT ON work_orders
  FOR EACH ROW EXECUTE FUNCTION set_sla_deadline();

-- ── TRIGGER: ticket_number ────────────────────────────

CREATE SEQUENCE IF NOT EXISTS ticket_seq START 1;

CREATE OR REPLACE FUNCTION generate_ticket_number()
RETURNS TRIGGER AS $$
DECLARE
  cr_code VARCHAR;
  yr      VARCHAR;
  seq_val BIGINT;
BEGIN
  SELECT type::TEXT INTO cr_code FROM control_rooms WHERE id = NEW.control_room_id;
  yr      := TO_CHAR(NOW(), 'YYYY');
  seq_val := NEXTVAL('ticket_seq');
  NEW.ticket_number := 'WO-' || yr || '-' || UPPER(cr_code) || '-' || LPAD(seq_val::TEXT, 5, '0');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ticket_number
  BEFORE INSERT ON work_orders
  FOR EACH ROW EXECUTE FUNCTION generate_ticket_number();

-- ══════════════════════════════════════════════════════
--  SEED DATA
-- ══════════════════════════════════════════════════════

INSERT INTO regions (id, name, name_ar, code)
VALUES ('11111111-1111-1111-1111-111111111111',
        'Jazan Health Cluster', 'تجمع جازان الصحي', 'JHC');

INSERT INTO centers (id, region_id, name, name_ar, code)
VALUES ('22222222-2222-2222-2222-222222222222',
        '11111111-1111-1111-1111-111111111111',
        'King Fahad Central Hospital – Jazan',
        'مستشفى الملك فهد المركزي – جازان', 'KFCH-JZ');

INSERT INTO control_rooms (id, center_id, type, name, name_ar)
VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
   '22222222-2222-2222-2222-222222222222',
   'residential', 'Residential Maintenance', 'صيانة الإسكان'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
   '22222222-2222-2222-2222-222222222222',
   'hospital', 'Hospital Maintenance', 'صيانة المستشفى'),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc',
   '22222222-2222-2222-2222-222222222222',
   'it', 'IT Maintenance', 'صيانة تقنية المعلومات'),
  ('dddddddd-dddd-dddd-dddd-dddddddddddd',
   '22222222-2222-2222-2222-222222222222',
   'medical', 'Medical Maintenance', 'الصيانة الطبية');

-- System admin (password: Admin@1234)
INSERT INTO users (id, username, email, password_hash, full_name, full_name_ar, role)
VALUES ('00000000-0000-0000-0000-000000000001',
        'sysadmin', 'admin@smms.sa',
        crypt('Admin@1234', gen_salt('bf', 12)),
        'System Administrator', 'مدير النظام', 'system_admin');
