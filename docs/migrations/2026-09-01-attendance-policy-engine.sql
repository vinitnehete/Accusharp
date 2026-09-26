-- Migration: the attendance policy engine
-- ================================================================
-- Context: docs/design/attendance-policy-engine.md, and the new "Attendance
-- policy engine" section in Attendance.md. Adds per-population attendance
-- rules (attendance_policy_rule) and the two trace tables that explain what
-- they did (attendance_policy_application, attendance_policy_outcome), plus
-- two columns on emp_monthly_attendance_summary.
--
-- This app runs with spring.jpa.hibernate.ddl-auto=update (README section 13),
-- so on any database - including one already in production - Hibernate creates
-- all three new tables, their indexes and their unique constraints by itself
-- the next time the app starts. A brand-new database and the test suite's H2
-- instance need nothing from this file at all.
--
-- Run the block below BY HAND against any database that existed before this
-- change, after the app has started at least once with the new code.
--
-- NOTHING IS SEEDED. attendance_policy_rule ships empty, deliberately: a
-- company that configures no rules must produce byte-identical attendance and
-- payroll output to what it produced before this feature existed, and a seeded
-- global default rule would break exactly that on the deploy that introduced
-- it. This is the opposite of attendance_rule, which does seed a global
-- default row - that one holds the values every company was already using
-- while they were hardcoded, so seeding it changed nothing. There is no such
-- prior behaviour to preserve here, because there was no such rule.

-- ---- 1. the two summary columns --------------------------------------
-- The ONLY step ddl-auto=update gets wrong, and it gets it wrong in a way
-- that breaks payroll on the first report after deploy.
--
-- Hibernate adds a new column to a populated table as NULLABLE, leaving every
-- existing summary row at NULL. PayrollService and the report services read
-- these straight into BigDecimal arithmetic, where a NULL is a
-- NullPointerException, not a zero. The DEFAULT 0.0 below makes the ALTER
-- backfill existing rows instead.
--
-- Same failure DailyAttendance.version's Javadoc already documents for
-- row_version, and the same fix. If the app has already started once with the
-- new code and created these columns as nullable, the two UPDATEs backfill
-- them; run them either way, they are idempotent.

ALTER TABLE emp_monthly_attendance_summary
    ADD COLUMN policy_lop_days DECIMAL(6,1) NOT NULL DEFAULT 0.0;

ALTER TABLE emp_monthly_attendance_summary
    ADD COLUMN comp_off_credit_days DECIMAL(6,1) NOT NULL DEFAULT 0.0;

UPDATE emp_monthly_attendance_summary SET policy_lop_days = 0.0 WHERE policy_lop_days IS NULL;
UPDATE emp_monthly_attendance_summary SET comp_off_credit_days = 0.0 WHERE comp_off_credit_days IS NULL;

-- ---- 2. confirm the rule table's constraints -------------------------
-- Hibernate creates these from the entity, but they are the load-bearing part
-- of the whole design and are worth verifying by eye rather than assuming.
--
-- uk_policy_rule_effective is what makes "two rules of the same type may not
-- both apply to one employee on one day" a database guarantee. It works
-- BECAUSE a rule version has no effective_to: a version runs until the next
-- version's effective_from, so overlap is impossible by construction and
-- uniqueness on the start date alone is sufficient. A from/to model could not
-- be enforced this way at all - non-overlapping date ranges need PostgreSQL's
-- EXCLUDE constraint, which MySQL 8 does not have, which would leave the
-- invariant that protects salaries resting on a service-layer check that
-- SalaryRule's Javadoc already documents as insufficient under concurrency.

SHOW INDEX FROM attendance_policy_rule WHERE Key_name = 'uk_policy_rule_effective';
SHOW INDEX FROM attendance_policy_rule WHERE Key_name = 'uk_policy_rule_version';
SHOW INDEX FROM attendance_policy_rule WHERE Key_name = 'idx_policy_rule_lookup';

-- If any of the three is missing (an older Hibernate, or a table created by
-- hand), add it:
--
-- ALTER TABLE attendance_policy_rule
--     ADD CONSTRAINT uk_policy_rule_effective
--     UNIQUE (company_id, scope, scope_ref, rule_type, effective_from);
--
-- ALTER TABLE attendance_policy_rule
--     ADD CONSTRAINT uk_policy_rule_version
--     UNIQUE (company_id, scope, scope_ref, rule_type, version);
--
-- ALTER TABLE attendance_policy_rule
--     ADD INDEX idx_policy_rule_lookup
--     (company_id, rule_type, scope, scope_ref, effective_from);

-- ---- 3. a note on what these constraints do NOT cover ----------------
-- company_id is nullable (NULL = a GLOBAL row, the shared fallback, the same
-- shape shift/category/department already use). MySQL and H2 both treat NULLs
-- in a unique index as DISTINCT, so the two constraints above do not stop two
-- GLOBAL rows of the same type and effective_from from coexisting.
--
-- Every per-company row - which is every row a tenant can create through the
-- API - is fully covered. The residual hole is reachable only by a platform
-- caller, is closed by a service-layer check in AttendancePolicyService, and
-- is stated here rather than papered over, in the same spirit as
-- Attendance.md's note that an unworkable rest gap is still not validated.
--
-- scope_ref is NOT NULL with a '*' sentinel for COMPANY and GLOBAL scopes
-- precisely to avoid this problem on the column where it would have mattered
-- most: a nullable scope_ref would have let two company-wide rules of the same
-- type and date coexist, which is the case a real tenant hits.

-- ---- 4. after ---------------------------------------------------------
-- Restart the app once so Hibernate re-validates the schema against the
-- entities. Nothing changes for any company until somebody creates a rule:
-- with no rows, resolution returns empty for every rule type, the day pipeline
-- short-circuits before touching the computed day, no trace rows are written,
-- and policy_lop_days stays 0.0. That property is covered by
-- PolicyEngineNoOpRegressionTest, which asserts byte-identical statuses,
-- summary figures, LOP and net pay against a fixture month.
