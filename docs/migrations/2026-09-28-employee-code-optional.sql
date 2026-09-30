-- Migration: the employee code is optional
-- ================================================================
-- Run BY HAND once, against any database that existed before this change.
-- A brand-new database and the H2 test database need nothing from this file.
--
-- ddl-auto=update never relaxes a NOT NULL column, so until this runs the
-- database refuses an employee saved without a code, even though the API and
-- the form now allow it. Existing codes are kept. The per-company unique key
-- (uk_employee_company_code) is unchanged: MySQL lets any number of rows have
-- no code, and a code that is given must still be unique in its company.

ALTER TABLE employee MODIFY COLUMN employee_code VARCHAR(50) NULL;

-- check
SHOW COLUMNS FROM employee LIKE 'employee_code';
