-- ══════════════════════════════════════════════════════
--  V2 Schema Migration
--  Brings init.sql (V1) up to match JPA entity definitions
--  Safe for postgres docker-entrypoint-initdb.d ordering:
--  runs AFTER init.sql which creates base tables and enums
-- ══════════════════════════════════════════════════════

-- ── New ENUMs ─────────────────────────────────────────

CREATE TYPE plan_type AS ENUM ('FREE','PRO','ENTERPRISE');
CREATE TYPE tenant_status AS ENUM ('ACTIVE','SUSPENDED','EXPIRED');
CREATE TYPE asset_status AS ENUM ('active','under_maintenance','broken');
CREATE TYPE inventory_movement_type AS ENUM ('stock_in','stock_out','adjustment');

-- ── NEW TABLE: tenants ────────────────────────────────

CREATE TABLE tenants (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  name       VARCHAR(200) NOT NULL UNIQUE,
  name_ar    VARCHAR(200),
  slug       VARCHAR(50)  UNIQUE,
  is_active  BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- ── NEW TABLE: tenant_subscriptions ──────────────────

CREATE TABLE tenant_subscriptions (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id  UUID NOT NULL UNIQUE REFERENCES tenants(id),
  plan_type  plan_type NOT NULL DEFAULT 'FREE',
  status     tenant_status NOT NULL DEFAULT 'ACTIVE',
  start_date TIMESTAMPTZ NOT NULL,
  end_date   TIMESTAMPTZ,
  notes      VARCHAR(500),
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- ── ADD tenant_id + missing cols to existing tables ──

ALTER TABLE regions
  ADD COLUMN tenant_id  UUID,
  ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();

ALTER TABLE centers
  ADD COLUMN tenant_id  UUID,
  ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW();

ALTER TABLE control_rooms
  ADD COLUMN tenant_id  UUID,
  ADD COLUMN code       VARCHAR(20),
  ADD COLUMN updated_at TIMESTAMPTZ DEFAULT NOW(),
  ADD COLUMN deleted_at TIMESTAMPTZ;

ALTER TABLE users
  ADD COLUMN tenant_id             UUID,
  ADD COLUMN updated_at            TIMESTAMPTZ DEFAULT NOW(),
  ADD COLUMN complex_name          VARCHAR(200),
  ADD COLUMN building_number       VARCHAR(50),
  ADD COLUMN unit_number           VARCHAR(50),
  ADD COLUMN department_name       VARCHAR(200),
  ADD COLUMN department_supervisor VARCHAR(200),
  ADD COLUMN department_email      VARCHAR(255),
  ADD COLUMN extension_number      VARCHAR(20),
  ADD COLUMN job_title             VARCHAR(200);

ALTER TABLE work_orders
  ADD COLUMN tenant_id  UUID;

ALTER TABLE ticket_history
  ADD COLUMN tenant_id  UUID;

ALTER TABLE attachments
  ADD COLUMN tenant_id  UUID;

ALTER TABLE notifications
  ADD COLUMN tenant_id  UUID;

-- ── NEW TABLE: departments ────────────────────────────

CREATE TABLE departments (
  id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id  UUID,
  center_id  UUID NOT NULL REFERENCES centers(id),
  name       VARCHAR(200) NOT NULL,
  name_ar    VARCHAR(200) NOT NULL,
  code       VARCHAR(40)  NOT NULL,
  is_active  BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW(),
  deleted_at TIMESTAMPTZ,
  UNIQUE(center_id, code)
);

-- ── NEW TABLE: assets ─────────────────────────────────

CREATE TABLE assets (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id     UUID,
  center_id     UUID NOT NULL REFERENCES centers(id),
  department_id UUID REFERENCES departments(id),
  name          VARCHAR(200) NOT NULL,
  name_ar       VARCHAR(200) NOT NULL,
  serial_number VARCHAR(120),
  location      VARCHAR(300),
  status        asset_status NOT NULL DEFAULT 'active',
  created_at    TIMESTAMPTZ DEFAULT NOW(),
  updated_at    TIMESTAMPTZ DEFAULT NOW(),
  deleted_at    TIMESTAMPTZ
);

CREATE INDEX idx_assets_center ON assets(center_id);

-- ── NEW TABLE: inventory_items ────────────────────────

CREATE TABLE inventory_items (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id     UUID,
  center_id     UUID NOT NULL REFERENCES centers(id),
  name          VARCHAR(200) NOT NULL,
  name_ar       VARCHAR(200) NOT NULL,
  sku           VARCHAR(80)  NOT NULL,
  unit          VARCHAR(40),
  quantity      NUMERIC(12,2) NOT NULL DEFAULT 0,
  reorder_level NUMERIC(12,2) NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ DEFAULT NOW(),
  updated_at    TIMESTAMPTZ DEFAULT NOW(),
  deleted_at    TIMESTAMPTZ,
  UNIQUE(center_id, sku)
);

-- ── NEW TABLE: inventory_movements ───────────────────

CREATE TABLE inventory_movements (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id     UUID,
  item_id       UUID NOT NULL REFERENCES inventory_items(id),
  work_order_id UUID REFERENCES work_orders(id),
  actor_id      UUID REFERENCES users(id),
  type          inventory_movement_type NOT NULL,
  quantity      NUMERIC(12,2) NOT NULL,
  note          TEXT,
  created_at    TIMESTAMPTZ DEFAULT NOW(),
  updated_at    TIMESTAMPTZ DEFAULT NOW(),
  deleted_at    TIMESTAMPTZ
);

CREATE INDEX idx_inventory_movements_item ON inventory_movements(item_id);

-- ── NEW TABLE: maintenance_types ─────────────────────

CREATE TABLE maintenance_types (
  id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  tenant_id        UUID,
  name             VARCHAR(200) NOT NULL,
  name_ar          VARCHAR(200) NOT NULL,
  control_room_type control_room_type NOT NULL,
  is_active        BOOLEAN DEFAULT TRUE,
  created_at       TIMESTAMPTZ DEFAULT NOW(),
  updated_at       TIMESTAMPTZ DEFAULT NOW(),
  deleted_at       TIMESTAMPTZ
);

-- ── TRIGGER: updated_at for new tables ───────────────
-- set_updated_at() function already created in V1 (init.sql)

CREATE TRIGGER trg_tenant_subscriptions_updated
  BEFORE UPDATE ON tenant_subscriptions
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_departments_updated
  BEFORE UPDATE ON departments
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_assets_updated
  BEFORE UPDATE ON assets
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_inventory_items_updated
  BEFORE UPDATE ON inventory_items
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_inventory_movements_updated
  BEFORE UPDATE ON inventory_movements
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_maintenance_types_updated
  BEFORE UPDATE ON maintenance_types
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── Additional indexes ────────────────────────────────

CREATE INDEX idx_regions_tenant    ON regions(tenant_id);
CREATE INDEX idx_centers_tenant    ON centers(tenant_id);
CREATE INDEX idx_users_tenant      ON users(tenant_id);
CREATE INDEX idx_work_orders_tenant ON work_orders(tenant_id);
