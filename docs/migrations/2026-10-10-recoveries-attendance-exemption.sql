-- Migration: loan recoveries and attendance exemption
-- ================================================================
-- Run BY HAND against an existing MySQL database before using the restored
-- features. Hibernate creates the two recovery tables and the nullable
-- payroll snapshot columns. A fresh database and the H2 test database need
-- nothing from this file.
--
-- The two new configuration columns need safe defaults on populated tables:
-- existing employees still use punches, and recoveries have a zero-percent
-- protected-net floor until the company chooses another value.

-- ---- 1. check the columns --------------------------------------------
SHOW COLUMNS FROM employee LIKE 'attendance_exempt';
SHOW COLUMNS FROM salary_rule LIKE 'recovery_minimum_net_percent';

-- If a column is absent, run its ADD before the UPDATE/MODIFY below:
-- ALTER TABLE employee
--     ADD COLUMN attendance_exempt BIT NOT NULL DEFAULT 0;
-- ALTER TABLE salary_rule
--     ADD COLUMN recovery_minimum_net_percent DECIMAL(6,2) NOT NULL DEFAULT 0.00;

-- ---- 2. backfill only missing configuration values --------------------
UPDATE employee SET attendance_exempt = 0 WHERE attendance_exempt IS NULL;
UPDATE salary_rule SET recovery_minimum_net_percent = 0.00
    WHERE recovery_minimum_net_percent IS NULL;

ALTER TABLE employee
    MODIFY COLUMN attendance_exempt BIT NOT NULL DEFAULT 0;
ALTER TABLE salary_rule
    MODIFY COLUMN recovery_minimum_net_percent DECIMAL(6,2) NOT NULL DEFAULT 0.00;

-- Historical payroll is immutable. Leave its new recovery balance/snapshot
-- columns NULL: those periods predate the ledger, and zero would falsely say
-- the employee had no balance. Do not manufacture loans or repayment rows
-- from old loan_deduction / advance_deduction figures.
