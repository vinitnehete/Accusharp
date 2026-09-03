-- Migration: employment-type behaviour as data
-- ================================================================
-- Context: docs/design/dynamic-configuration.md, and ARCHITECTURE.md's
-- "Employment types are configurable" section. Turns the seven hardcoded
-- DAY_WISE-vs-everyone-else branches in PayrollService.build into a row a
-- company can edit, and lets a company define employment types beyond the four
-- in the EmployeeStatus enum.
--
-- This app runs with spring.jpa.hibernate.ddl-auto=update, so Hibernate creates
-- the employment_type table and adds the four new nullable columns
-- (employee.employment_type_id, payroll.employment_type_code,
-- payroll.pay_basis, payroll.payable_days_cap) by itself on the next startup.
-- A brand-new database and the test suite need nothing from this file.
--
-- NOTHING IS SEEDED AND NOTHING IS BACKFILLED, and that is the whole safety
-- property of this change:
--
--   employee.employment_type_id stays NULL for every existing row, and
--   PayBehaviourResolver falls back to the legacy EmployeeStatus semantics
--   whenever it is NULL - reproducing today's arithmetic exactly.
--
-- So an existing client's next payroll run is byte-identical whether or not
-- anybody ever creates an employment_type row. Adoption is opt-in PER EMPLOYEE,
-- not a migration that must complete before the next pay cycle, and rolling
-- back is clearing one column. Proven by
-- EmploymentTypePayrollTest.noEmploymentTypeFallsBackToTheLegacyEnum and by
-- every pre-existing payroll test, none of which assigns a type.

-- ---- 1. verify the new columns landed as NULLABLE ---------------------
-- All four MUST stay nullable. A NOT NULL default here would force every
-- existing employee onto a type before anybody had decided which one, and
-- would silently change how their payroll is computed on the next run.

SHOW COLUMNS FROM employee LIKE 'employment_type_id';
SHOW COLUMNS FROM payroll  LIKE 'employment_type_code';
SHOW COLUMNS FROM payroll  LIKE 'pay_basis';
SHOW COLUMNS FROM payroll  LIKE 'payable_days_cap';

-- Expect Null = YES on all four. If any reads NO, drop the constraint:
-- ALTER TABLE employee MODIFY employment_type_id BIGINT NULL;

-- ---- 2. verify the unique constraint ----------------------------------
-- Per-company codes, the same shape shift/category/department have used since
-- the 2026-08-08 migration - two companies must be able to define their own
-- CONTRACT with different meanings, which is the point of the feature.

SHOW INDEX FROM employment_type WHERE Key_name = 'uk_employment_type_company_code';

-- If missing:
-- ALTER TABLE employment_type
--     ADD CONSTRAINT uk_employment_type_company_code UNIQUE (company_id, type_code);

-- ---- 3. adopting the feature (OPTIONAL, per company) ------------------
-- Do this through the API rather than by hand, so the tenant checks, the
-- validation and the audit trail all apply:
--
--   POST /api/employment-types/seed-defaults
--
-- creates four types - PERMANENT, DAY_WISE, CONTRACT, INTERN - carrying exactly
-- the behaviour the enum hardcodes today. It is idempotent and never overwrites
-- a type that already exists, so a company that has already customised DAY_WISE
-- cannot have it reset by someone running it again.
--
-- Note the seeded DAY_WISE leaves payable_days_cap NULL rather than storing 26.
-- NULL means "inherit this company's salary_rule.day_wise_days_in_month", which
-- is where that number lives today; freezing a copy of it here would silently
-- stop tracking the salary rule the first time somebody changed it.
--
-- Assigning a type to an employee is then a normal employee update:
--
--   PUT /api/employees/{id}   { ..., "employmentTypeId": 3 }
--
-- Until that field is set, that employee is paid by the legacy enum, exactly as
-- before.

-- ---- 4. what NOT to do ------------------------------------------------
-- Do NOT bulk-assign employment_type_id with an UPDATE ... JOIN on
-- employment_status. It looks equivalent and is not: it moves every employee
-- onto the configured path in one step, so any later edit to a type - including
-- a mistaken one - immediately reprices everybody, with no employee left on the
-- legacy fallback to compare against. Assign a few employees, run a payroll,
-- compare it against the previous month, and widen from there.

-- ---- 5. after ---------------------------------------------------------
-- Restart the app once so Hibernate re-validates the schema against the
-- entities. Payroll generated before this change keeps payroll.pay_basis NULL;
-- the reports fall back to payroll.employment_status for those rows
-- (Payroll.wasPaidPerAttendedDay), so historical payslips and registers render
-- exactly as they always did.
