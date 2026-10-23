-- V3: add the 'other' user_role value (was DataInitializer.migrateEnums).
-- Precondition: the V2 schema must exist. If it does not, Flyway baselined a pre-V2 database
-- at version 2 by mistake; stop here instead of guessing.
DO $$
BEGIN
  IF to_regclass('public.tenants') IS NULL THEN
    RAISE EXCEPTION 'V2 schema missing: tenants table not found; DB is pre-V2, stop and apply scripts/V2 manually after review';
  END IF;
END $$;

ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'other';
