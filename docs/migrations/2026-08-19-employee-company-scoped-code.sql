-- Migration: make employee_code per-company (userId stays global)
-- ================================================================
-- Context: full-application security audit's Database Assessment finding
-- ("Employee identity is unique platform-wide, not per company") - the same
-- class of bug 2026-08-08-per-company-masters.sql already fixed for
-- department/designation/shift, applied here to employee.employee_code.
--
-- employee.user_id is DELIBERATELY left globally unique, not moved to
-- per-company - see Employee.java's uk_employee_user_id Javadoc. Login
-- (AuthService#dispatchLogin) and the biometric device feed both resolve an
-- employee by userId alone, with no company selector in either flow; making
-- userId per-company would make login ambiguous between two companies that
-- happened to pick the same userId. employee_code has no such constraint -
-- it's a display/reporting code, never looked up across companies - so only
-- it moves to (company_id, employee_code), the same shape as the other
-- three masters.
--
-- This app runs with spring.jpa.hibernate.ddl-auto=update, so nothing here
-- needs to run against a brand-new database (including the test suite's H2
-- instance) - Hibernate builds the correct composite constraint from a
-- clean slate. Run the block below BY HAND against any database that
-- existed before this change, once the app has started at least once with
-- the new code.
--
-- The old constraint's name isn't predictable - depends on the MySQL/
-- Hibernate version that first created the table. Find it with SHOW INDEX
-- before you drop it; do not guess the name from this file.

SHOW INDEX FROM employee WHERE Non_unique = 0 AND Column_name = 'employee_code';
-- Note the Key_name from the row above (commonly `employee_code`,
-- `uk_employee_code`, or a generated UK_... name), then:
-- ALTER TABLE employee DROP INDEX <key_name_from_above>;
ALTER TABLE employee
    ADD CONSTRAINT uk_employee_company_code UNIQUE (company_id, employee_code);

-- After the DROP/ADD pair, restart the app once so Hibernate re-validates
-- the schema against the entity. No data backfill needed - every existing
-- row already has whatever company_id it had before.
