-- V24: link existing department users and tickets to department rows (data only, nothing guessed).
--  1. users.department_id for role = 'department' users that have none yet, ONLY when exactly one
--     non-deleted department matches (same tenant, same center, case/space-insensitive name, or the
--     same name_ar). Zero or several matches leave the user unlinked (counted, never picked).
--  2. work_orders.department_id from the requester's department for tickets created in the
--     'department' role, only when both rows have the same non-null tenant and the department is
--     in the ticket's center.
-- Never inserts departments. Visibility rules do not change: department users still see only
-- their own submissions.

DO $$
DECLARE
  linked     INT;
  unmatched  INT;
  ambiguous  INT;
  wo_linked  INT;
BEGIN
  WITH cand AS (
    SELECT u.id AS user_id,
           (SELECT count(*) FROM departments d
             WHERE d.deleted_at IS NULL AND d.tenant_id = u.tenant_id AND d.center_id = u.center_id
               AND lower(btrim(d.name)) = lower(btrim(u.department_name))) AS n_match
    FROM users u
    WHERE u.role = 'department' AND u.department_id IS NULL AND u.deleted_at IS NULL
      AND u.tenant_id IS NOT NULL AND u.center_id IS NOT NULL
      AND u.department_name IS NOT NULL AND btrim(u.department_name) <> ''
  ), upd AS (
    UPDATE users u SET department_id = (
        SELECT d.id FROM departments d
         WHERE d.deleted_at IS NULL AND d.tenant_id = u.tenant_id AND d.center_id = u.center_id
           AND lower(btrim(d.name)) = lower(btrim(u.department_name)))
    FROM cand WHERE cand.user_id = u.id AND cand.n_match = 1
    RETURNING 1
  )
  SELECT count(*) INTO linked FROM upd;

  SELECT count(*) INTO ambiguous FROM users u
   WHERE u.role = 'department' AND u.department_id IS NULL AND u.deleted_at IS NULL
     AND u.tenant_id IS NOT NULL AND u.center_id IS NOT NULL AND btrim(coalesce(u.department_name, '')) <> ''
     AND (SELECT count(*) FROM departments d
           WHERE d.deleted_at IS NULL AND d.tenant_id = u.tenant_id AND d.center_id = u.center_id
             AND lower(btrim(d.name)) = lower(btrim(u.department_name))) > 1;
  SELECT count(*) INTO unmatched FROM users u
   WHERE u.role = 'department' AND u.department_id IS NULL AND u.deleted_at IS NULL;
  unmatched := unmatched - ambiguous;

  WITH upd2 AS (
    UPDATE work_orders w SET department_id = u.department_id
    FROM users u, departments d
    WHERE w.requested_by_id = u.id AND w.created_by_role = 'department' AND w.department_id IS NULL
      AND u.department_id IS NOT NULL AND d.id = u.department_id
      AND w.tenant_id IS NOT NULL AND w.tenant_id = u.tenant_id AND w.tenant_id = d.tenant_id
      AND d.center_id = w.center_id
    RETURNING 1
  )
  SELECT count(*) INTO wo_linked FROM upd2;

  RAISE NOTICE 'V24 departments: % user(s) linked, % user(s) without a unique name match left unlinked (of which % ambiguous), % ticket(s) linked',
    linked, unmatched, ambiguous, wo_linked;
END $$;
