-- Migration: make department / designation / shift per-company
-- ================================================================
-- Context: SECURITY_AUDIT.md's "Department/Designation/Shift are still
-- global masters" finding (see SECURITY.md Phase 6). These three tables
-- moved from one global row per code to one row per (company, code), the
-- same shape `holiday` already uses.
--
-- This app runs with spring.jpa.hibernate.ddl-auto=update (see README
-- section 13), which will add the new nullable `company_id` column to each
-- table automatically the next time the app starts - no manual step needed
-- for that part, on any database, including one already running in
-- production.
--
-- What ddl-auto=update will NOT do is drop the pre-existing single-column
-- UNIQUE index on department_code / designation_code / shift_code. Left in
-- place, that old index keeps every code globally unique across all
-- companies, exactly as before - meaning two companies still can't use the
-- same department/shift code, which defeats the point of this migration.
--
-- Run the three blocks below BY HAND against any database that existed
-- before this change, after the app has started at least once with the new
-- code (so the company_id column already exists) and BEFORE you rely on two
-- companies sharing a code. A brand-new database (or the test suite's H2
-- instance, which is always created fresh) does not need this file at all -
-- Hibernate builds the correct composite constraint from a clean slate.
--
-- The old constraint's name isn't predictable - it depends on the MySQL/
-- Hibernate version that first created the table. Find it with SHOW INDEX
-- before you drop it; do not guess the name from this file.

-- ---- department ------------------------------------------------
SHOW INDEX FROM department WHERE Non_unique = 0 AND Column_name = 'department_code';
-- Note the Key_name from the row above (commonly `department_code` or a
-- generated UK_... name), then:
-- ALTER TABLE department DROP INDEX <key_name_from_above>;
ALTER TABLE department
    ADD CONSTRAINT uk_department_company_code UNIQUE (company_id, department_code);

-- ---- designation -------------------------------------------------
SHOW INDEX FROM designation WHERE Non_unique = 0 AND Column_name = 'designation_code';
-- ALTER TABLE designation DROP INDEX <key_name_from_above>;
ALTER TABLE designation
    ADD CONSTRAINT uk_designation_company_code UNIQUE (company_id, designation_code);

-- ---- shift ---------------------------------------------------------
SHOW INDEX FROM shift WHERE Non_unique = 0 AND Column_name = 'shift_code';
-- ALTER TABLE shift DROP INDEX <key_name_from_above>;
ALTER TABLE shift
    ADD CONSTRAINT uk_shift_company_code UNIQUE (company_id, shift_code);

-- After running all three DROP/ADD pairs, restart the app once so Hibernate
-- re-validates the schema against the entities. The four seeded shifts and
-- any pre-existing department/designation rows keep company_id = NULL,
-- which is exactly the "shared, read-only-to-companies" state described in
-- SECURITY.md - no data backfill is required.
