-- ---------------------------------------------------------------------------
-- Simulates the failure that motivated attendance regularisation.
--
--   Employee : EMP005 (Priya Kulkarni)
--   Day      : 2026-09-25
--
-- The device captured the entry punch and never the matching exit. With only
-- one punch in the shift window the day is an INVALID_PUNCH, so a day actually
-- worked silently becomes loss of pay - which is exactly what an admin has to
-- be able to correct.
--
-- Run this AFTER device_logs_EMP005_2026-09.sql and AFTER the first attendance
-- generation, then regenerate to pick the change up.
--
--   mysql -uroot -proot alsama < device_logs_EMP005_missing_out_punch.sql
--
-- Effect on the month (leave already approved):
--   before : presentDays 22 | lopDays 1
--   after  : presentDays 21 | lopDays 2   <- the 25th is now an invalid punch
--
-- To put it back, either rerun device_logs_EMP005_2026-09.sql or correct the
-- day through PUT /api/attendance/EMP005/2026-09-25.
-- ---------------------------------------------------------------------------

DELETE FROM device_logs
WHERE user_id = 'EMP005'
  AND log_date = '2026-09-25 15:00:00';
