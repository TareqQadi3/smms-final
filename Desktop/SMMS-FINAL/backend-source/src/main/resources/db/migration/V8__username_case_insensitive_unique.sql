-- V8: prevent look-alike accounts ('Admin' vs 'admin'). Created only when no case-insensitive
-- duplicates exist; otherwise skipped with a warning so the migration never fails or deletes data.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM users WHERE deleted_at IS NULL GROUP BY lower(username) HAVING count(*) > 1) THEN
    RAISE WARNING 'V8 skipped: case-insensitive duplicate usernames exist; resolve them, then create uq_users_username_lower manually';
  ELSE
    CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_lower ON users (lower(username)) WHERE deleted_at IS NULL;
  END IF;
END $$;
