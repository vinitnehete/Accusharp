-- Migration: the sandwich leave rule (RuleType.SANDWICH_LEAVE)
-- ================================================================
-- Context: Attendance.md section 11, "The sandwich leave rule".
--
-- Run BY HAND once, against any database that existed before this change,
-- after the app has started at least once with the new code. A brand-new
-- database and the H2 test database need nothing from this file.
--
-- NOTHING IS SWITCHED ON. The rule does nothing until a company saves a
-- SANDWICH_LEAVE rule (Rules -> Sandwich leave), so a company that never does
-- produces exactly the attendance and payroll it produced before.

-- ---- 1. rule_type must accept the new value -----------------------------
-- The one step that is NOT optional. Hibernate 7 created these three columns
-- as MySQL enum('MISSING_PUNCH', ..., 'LATE_MARK_ACCUMULATION'), and
-- ddl-auto=update never widens an existing column. Until this runs, switching
-- the rule on fails with "Data truncated for column 'rule_type'".
--
-- They become plain text, matching the new @JdbcTypeCode(VARCHAR) mapping, so
-- the next rule type will not need this again. Existing values are kept.

ALTER TABLE attendance_policy_rule        MODIFY COLUMN rule_type VARCHAR(40) NOT NULL;
ALTER TABLE attendance_policy_application MODIFY COLUMN rule_type VARCHAR(40) NOT NULL;
ALTER TABLE attendance_policy_outcome     MODIFY COLUMN rule_type VARCHAR(40) NOT NULL;

-- ---- 2. the new summary column ------------------------------------------
-- ddl-auto=update adds it with DEFAULT 0 (columnDefinition on the entity), so
-- this normally already exists. Only needed if the app has not started yet.
--
-- ALTER TABLE emp_monthly_attendance_summary
--     ADD COLUMN sandwich_holiday_days DECIMAL(6,1) NOT NULL DEFAULT 0;

-- ---- 3. check ------------------------------------------------------------
SHOW COLUMNS FROM attendance_policy_rule LIKE 'rule_type';
SHOW COLUMNS FROM emp_monthly_attendance_summary LIKE 'sandwich_holiday_days';
