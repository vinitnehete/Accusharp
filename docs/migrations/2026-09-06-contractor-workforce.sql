-- Migration: labour contractors and the workforce they deploy
-- ================================================================
-- Context: ARCHITECTURE.md's "Labour contractors" section. A company can now
-- onboard the agencies supplying it labour, register the workers each one
-- deploys, roster those workers onto its own shifts, generate their
-- attendance, and send each contractor a report to run their payroll from.
--
-- This app runs with spring.jpa.hibernate.ddl-auto=update (README section
-- 13), so on any existing database Hibernate creates the new `contractor`
-- table and adds the new nullable `employee.contractor_id` column (plus its
-- foreign key and index) automatically on the next startup. There is no
-- mandatory manual step in this migration - unlike
-- 2026-08-08-per-company-masters.sql, nothing pre-existing has to be
-- dropped, because both additions are new.
--
-- WHY EXISTING DATA IS UNAFFECTED
-- --------------------------------
-- A contractor's worker is an `employee` row carrying a non-null
-- `contractor_id`; every employee that existed before this change has NULL
-- there and is therefore, by definition, the company's own staff. The two
-- company-scoped choke points in EmployeeService - getActiveEntities() and
-- getAllEntities(), which payroll, the dashboard, every report and the
-- employee directory all read - now filter on `contractor_id IS NULL`, so a
-- database with no contractor rows returns byte-identical results to what it
-- returned before. Adoption is opt-in, and rolling back is dropping one
-- column.
--
-- The statements below are the schema Hibernate will produce, written out
-- for a DBA who would rather apply it by hand, or verify it afterwards. They
-- are needed only against a database where the app has NOT yet started with
-- the new code; a fresh database (and the test suite's H2 instance, always
-- created from scratch) never needs this file at all.

-- 1. The contractor itself. Always belongs to exactly one company - unlike
--    department/designation/shift there is no shared (company_id IS NULL)
--    catalog row, because a contractor is a commercial relationship between
--    two companies, never a reference row other companies could read.
CREATE TABLE contractor (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    company_id           BIGINT       NOT NULL,
    contractor_code      VARCHAR(30)  NOT NULL,
    contractor_name      VARCHAR(255) NOT NULL,
    contact_person       VARCHAR(255),
    email                VARCHAR(255),
    phone                VARCHAR(20),
    address              VARCHAR(500),
    gst_no               VARCHAR(20),
    pan_no               VARCHAR(20),
    agreement_start_date DATE,
    agreement_end_date   DATE,
    notes                VARCHAR(500),
    record_status        VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    -- Per-company, not global: two companies numbering their own contractors
    -- "C01" independently is the expected case, the same reasoning as
    -- employee_code in 2026-08-19-employee-company-scoped-code.sql.
    CONSTRAINT uk_contractor_company_code UNIQUE (company_id, contractor_code),
    CONSTRAINT fk_contractor_company FOREIGN KEY (company_id) REFERENCES company (id)
);

CREATE INDEX idx_contractor_company ON contractor (company_id);

-- 2. The one column that separates the two populations. NULL means the
--    company's own employee - which is every row that already exists.
ALTER TABLE employee
    ADD COLUMN contractor_id BIGINT NULL,
    ADD CONSTRAINT fk_employee_contractor FOREIGN KEY (contractor_id) REFERENCES contractor (id);

-- Every contractor-scoped read filters on this column, and every
-- company-scoped read filters on it being NULL - so it sits on the hot path
-- of payroll, the dashboard and the employee directory, not only the
-- contractor screens.
CREATE INDEX idx_employee_contractor ON employee (contractor_id);

-- 3. Nothing to backfill. Existing employees keep contractor_id NULL, which
--    is exactly "this is our own employee", and the two new permission codes
--    (CONTRACTOR_READ, CONTRACTOR_MANAGE) are seeded on startup by
--    PermissionSeeder like every other code - that seeder deletes and
--    re-inserts every role grant on each boot, so HR/ADMIN pick up both and
--    SUPERVISOR picks up CONTRACTOR_READ with no manual step.

-- ROLLBACK
-- --------
-- Dropping the column restores the previous behaviour; the contractor rows
-- can be left in place or dropped afterwards.
--
--   ALTER TABLE employee DROP FOREIGN KEY fk_employee_contractor;
--   ALTER TABLE employee DROP COLUMN contractor_id;
--   DROP TABLE contractor;
--
-- Note this orphans any attendance already generated for a contractor's
-- workers: emp_attendance_shift and emp_daily_attendance are keyed by
-- user_id, so those rows survive the drop and would then read as belonging
-- to employees with no salary structure - which payroll's generate-all would
-- pick up. Deactivate the workers (record_status = 'INACTIVE') before
-- rolling back if that matters.
