# Accusharp HRMS - User Guide

A Human Resource Management System covering the full employee lifecycle: org
masters, supervisor mapping, shift scheduling, biometric attendance, leave,
loss of pay, payroll, salary slips, reports and a dashboard.

| Doc | Read it when |
|---|---|
| **[WALKTHROUGH.md](WALKTHROUGH.md)** | **Start here.** One complete cycle for one employee, explained |
| **[TESTING.md](TESTING.md)** | You want every URL, header, body and response to test in Postman + SQL |
| **README.md** (this file) | Day-to-day reference |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | How it is built and why |

Ready-to-use test assets: a [Postman collection](docs/testing/Accusharp-HRMS.postman_collection.json)
(51 requests, 9 ordered folders) and [punch SQL](docs/testing/device_logs_EMP005_2026-09.sql).

- **Stack**: Java 21, Spring Boot 4.1.0, MySQL, Lombok
- **Base URL**: `http://localhost:8080`

---

## Contents

1. [Starting the application](#1-starting-the-application)
2. [The order everything happens in](#2-the-order-everything-happens-in)
3. [One-time setup](#3-one-time-setup)
4. [Every month: schedule shifts](#4-every-month-schedule-shifts)
5. [Biometric punches](#5-biometric-punches)
6. [Check attendance](#6-check-attendance)
7. [Leave](#7-leave)
8. [Run payroll](#8-run-payroll)
9. [Salary slips](#9-salary-slips)
10. [Reports and dashboard](#10-reports-and-dashboard)
11. [Full API reference](#11-full-api-reference)
12. [Troubleshooting](#12-troubleshooting)
13. [Before going live](#13-before-going-live)

---

## 1. Starting the application

### With MySQL (default)

Needs MySQL on `localhost:3306`. The schema `alsama` is created automatically if
missing; credentials are in
[`application.properties`](src/main/resources/application.properties).

```bash
cd /Users/vinitnehete/Downloads/Accusharp && ./mvnw spring-boot:run
```

### Without MySQL (in-memory H2)

Good for trying things out. Everything is wiped on restart.

```bash
cd /Users/vinitnehete/Downloads/Accusharp && ./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

### Run the tests

```bash
cd /Users/vinitnehete/Downloads/Accusharp && ./mvnw test
```

### What gets seeded

On an **empty** database the app creates:

| | |
|---|---|
| Shifts | `MORNING` 06:00-15:00, `GENERAL` 09:00-18:00, `EVENING` 14:00-23:00, `NIGHT` 18:00-08:00 |
| Salary rule | Basic 50%, HRA 40%, Conveyance 10%, Education 10%, PF 12%, ESIC 0.75% |
| Demo org | Company `ACC`, departments `PROD`/`ADMIN`, and 4 employees |

Demo employees: `HR001` (Meera Joshi, HR), `SUP001` (Rakesh Patil, supervisor,
reports to HR001), `EMP001` (Sunil Kadam, permanent), `EMP002` (Anita Shinde,
day-wise). Both employees report to `SUP001`.

Turn seeding off with `hrms.seed.enabled=false` before loading real data.

---

## 2. The order everything happens in

```
Masters -> Employees -> SHIFT ROSTER -> Punches -> Leave approvals -> Payroll -> Slips
```

Two steps are easy to skip and both silently wreck the numbers:

- **No roster** -> nobody has any expected working days, so everyone reads as
  absent and the whole month becomes loss of pay.
- **Unapproved leave** -> those days count as absence and land in LOP instead of
  being paid.

---

## 3. One-time setup

### 3.1 Check the salary rule first

The salary breakup is calculated **at the moment an employee is written**.
Changing a percentage later only affects employees you create or update
afterwards - existing records keep what was computed at the time.

So set this before creating anyone:

```bash
curl http://localhost:8080/api/salary-rules
```

To change it:

```bash
curl -X PUT http://localhost:8080/api/salary-rules -H 'Content-Type: application/json' -d '{"basicDaPercent":50,"hraPercent":40,"conveyancePercent":10,"educationPercent":10,"pfPercent":12,"esicPercent":0.75,"esicWageCeiling":21000,"ptUpperThreshold":10001,"ptUpperAmount":200,"ptLowerThreshold":7501,"ptLowerAmount":175,"dayWiseDaysInMonth":26,"standardHoursPerDay":8,"overtimeRateMultiplier":1.00}'
```

| Field | Meaning |
|---|---|
| `basicDaPercent` | Basic + DA as a share of gross salary |
| `hraPercent`, `conveyancePercent`, `educationPercent` | Shares of **basic**, not gross |
| `pfPercent` | PF rate, applied to the prorated PF basic |
| `esicPercent` / `esicWageCeiling` | ESIC rate; no ESIC above the ceiling |
| `pt*Threshold` / `pt*Amount` | Professional tax slab on gross salary |
| `dayWiseDaysInMonth` | Payable-day base for `DAY_WISE` staff (26 by convention) |
| `standardHoursPerDay` | Divisor for the per-hour overtime rate |
| `overtimeRateMultiplier` | `1.00` = plain rate, `1.50` = time-and-a-half |

### 3.2 Company

```bash
curl -X POST http://localhost:8080/api/companies -H 'Content-Type: application/json' -d '{"companyCode":"ACC","companyName":"Accusharp Industries","address":"Pune, Maharashtra","phone":"020-00000000","email":"hr@accusharp.example","status":"ACTIVE"}'
```

### 3.3 Departments and designations

```bash
curl -X POST http://localhost:8080/api/departments -H 'Content-Type: application/json' -d '{"departmentCode":"PROD","departmentName":"Production","description":"Shop floor"}'
```

```bash
curl -X POST http://localhost:8080/api/designations -H 'Content-Type: application/json' -d '{"designationCode":"OPR","designationName":"Machine Operator"}'
```

Note the `id` each returns - you need them for employees.

### 3.4 Employees

Create senior people first, so juniors can point at them as supervisor.

```bash
curl -X POST http://localhost:8080/api/employees -H 'Content-Type: application/json' -d '{"userId":"EMP003","employeeCode":"EMP-003","employeeName":"Ravi Deshmukh","companyId":1,"departmentId":1,"designationId":1,"supervisorUserId":"SUP001","joiningDate":"2024-05-01","dateOfBirth":"1995-08-14","status":"PERMANENT","role":"EMPLOYEE","email":"ravi@accusharp.example","phone":"9876543210","grossSalary":24000,"pfBasic":9000,"medicalAllowance":1250,"otherAllowance":0,"overtimeEligible":true}'
```

**`userId` must equal the biometric device's user id.** That single field is what
joins the device, attendance, leave and payroll together. Get it wrong and the
employee will show zero attendance forever.

You send only these money fields:

| You send | Server derives |
|---|---|
| `grossSalary`, `pfBasic`, `medicalAllowance`, `otherAllowance` | `basicDA`, `hra`, `conveyanceAllowance`, `educationAllowance`, `grossSalaryWage` |

Sending a derived field is rejected - it is not on the request DTO at all.

**`status`** decides how the person is paid:

| Value | Paid against |
|---|---|
| `PERMANENT`, `CONTRACT`, `INTERN` | The month's working days, reduced by LOP |
| `DAY_WISE` | Days actually attended, over a fixed 26-day base |

**`role`** decides what they may do: `ADMIN`, `HR`, `SUPERVISOR`, `EMPLOYEE`.
Only HR and admin can give final leave approval; a supervisor can only schedule
and approve their own team.

**`overtimeEligible`** must be `true` for overtime to be paid. Overtime hours are
still *measured* for everyone - they just are not paid to ineligible staff.

Other employee operations:

```bash
curl http://localhost:8080/api/employees/by-user-id/EMP003
```

```bash
curl http://localhost:8080/api/employees/SUP001/team
```

```bash
curl -X PATCH "http://localhost:8080/api/employees/EMP003/supervisor?supervisorUserId=SUP001"
```

`DELETE /api/employees/{id}` **deactivates** rather than deletes - payroll
history has to keep resolving names.

### 3.5 Holidays

Load the year's calendar up front. A mandatory holiday is removed from working
days, so it can never turn into LOP.

```bash
curl -X POST http://localhost:8080/api/holidays -H 'Content-Type: application/json' -d '{"companyId":1,"holidayName":"Independence Day","holidayDate":"2026-08-15","optionalHoliday":false}'
```

Set `optionalHoliday: true` for restricted holidays - those stay working days
unless the employee actually takes leave.

### 3.6 Shifts (only if the four defaults do not fit)

```bash
curl -X POST http://localhost:8080/api/shifts -H 'Content-Type: application/json' -d '{"shiftCode":"NIGHT_B","shiftName":"Night B","startTime":"20:00:00","endTime":"05:00:00","workingHours":8,"breakMinutes":45,"graceMinutes":10,"overtimeWindowMinutes":240}'
```

| Field | Meaning |
|---|---|
| `workingHours` | Paid hours. Anything beyond this is overtime |
| `breakMinutes` | Unpaid break, subtracted from the punch-to-punch span |
| `graceMinutes` | Tolerance before an arrival counts as late |
| `overtimeWindowMinutes` | How long after the scheduled end a punch still counts. Default 240 (4h). **Set this to cover your longest realistic overtime** - an exit punched beyond it is invisible and the day reads as a single-punch error. Keep it shorter than the gap to the employee's next shift. |

A shift whose `endTime` is **not after** its `startTime` automatically crosses
midnight - `18:00 -> 08:00` is handled correctly, and the day still belongs to
the date the shift started.

---

## 4. Every month: schedule shifts

Nothing works without a roster. An unscheduled day is not an attendance day.

### Bulk assignment (the usual one)

```bash
curl -X POST http://localhost:8080/api/shift-schedules/bulk -H 'Content-Type: application/json' -d '{"userIds":["EMP001","EMP003"],"fromDate":"2026-09-01","toDate":"2026-09-30","shiftCode":"MORNING","weekOffDays":["SUNDAY"],"skipHolidays":true,"overwriteExisting":true,"assignedBy":"SUP001"}'
```

| Field | Effect |
|---|---|
| `weekOffDays` | Those weekdays are scheduled but marked as weekly off |
| `skipHolidays` | Mandatory holidays are not scheduled at all |
| `overwriteExisting` | `false` returns 409 on any day already scheduled |
| `assignedBy` | If a supervisor, they may only schedule their own team |

### Auto-rotation

Each employee starts on a different shift and everyone advances one position
every `rotationDays`, so the team stays spread across shifts instead of moving
together.

```bash
curl -X POST http://localhost:8080/api/shift-schedules/auto-rotate -H 'Content-Type: application/json' -d '{"userIds":["EMP001","EMP002","EMP003"],"shiftCycle":["MORNING","EVENING","NIGHT"],"fromDate":"2026-09-01","toDate":"2026-09-30","rotationDays":7,"weekOffDays":["SUNDAY"],"skipHolidays":true,"assignedBy":"SUP001"}'
```

### Copy last month

Copies by day-of-month; days that do not exist in a shorter target month are
skipped.

```bash
curl -X POST http://localhost:8080/api/shift-schedules/copy-month -H 'Content-Type: application/json' -d '{"userIds":["EMP001","EMP003"],"sourceMonth":"2026-08","targetMonth":"2026-09","overwriteExisting":false,"assignedBy":"SUP001"}'
```

### Swap two people on a date

```bash
curl -X POST http://localhost:8080/api/shift-schedules/swap -H 'Content-Type: application/json' -d '{"firstUserId":"EMP001","secondUserId":"EMP003","shiftDate":"2026-09-10","assignedBy":"SUP001"}'
```

### Apply the holiday calendar to an existing roster

Marks every mandatory holiday in the month as a weekly off across the roster.

```bash
curl -X POST "http://localhost:8080/api/shift-schedules/holiday-override?month=2026-09"
```

### Check the result

```bash
curl "http://localhost:8080/api/shift-schedules/planner?month=2026-09"
```

Returns one row per employee and one column per date, with the shift code or
`WO` for a weekly off. Add `&supervisorUserId=SUP001` for just one team.

---

## 5. Biometric punches

**There is no endpoint to create or edit a punch, by design.** The eSSL device's
middleware writes straight into the `device_logs` table; this application only
reads it.

Columns: `device_log_id` (primary key), `device_id`, `user_id`, `log_date`.

To test without a device, insert rows yourself:

```bash
mysql -uroot -proot alsama -e "INSERT INTO device_logs (device_log_id, device_id, user_id, log_date) VALUES (900001,1,'EMP001','2026-09-01 06:00:00'),(900002,1,'EMP001','2026-09-01 15:00:00');"
```

`user_id` here must match the employee's `userId` exactly.

### How punches are read

- Punches are matched to the scheduled shift's window: **60 minutes before** the
  start (people badge in early) and up to `overtimeWindowMinutes` **after** the
  scheduled end (people stay late). The window is deliberately not symmetric -
  a late exit is overtime, not a missing punch.
- **First punch = in, last punch = out.**
- With 4+ punches, the gaps in the middle are treated as the real break.
  Otherwise the shift's configured `breakMinutes` is used.
- Worked time = span between first and last punch, minus break.
- 75% of the shift = full day, 40% = half day, below that = absent.
- **One lone punch** is flagged `INVALID_PUNCH` - a device or user error, not an
  absence. These need fixing before payroll.

---

## 6. Check attendance

Do this before payroll every month. It is where you catch problems while they
are still fixable.

```bash
curl "http://localhost:8080/api/attendance/EMP001/monthly?month=2026-09"
```

Returns the month's totals plus a day-by-day breakdown:

| Field | Meaning |
|---|---|
| `workingDays` | Scheduled days that were not a weekly off or holiday |
| `presentDays` | Attended days; half days count as 0.5 |
| `absentDays` | Expected to work, did not, and had no leave |
| `leaveDays` / `lopDays` | Approved leave on working days / unpaid days |
| `lateCount`, `earlyExitCount` | Exception counts |
| `invalidPunches` | **Check this first** - days with a single punch |
| `totalHours`, `overtimeHours` | Measured against each day's own shift length |

Day range instead of a whole month:

```bash
curl "http://localhost:8080/api/attendance/EMP001?fromDate=2026-09-01&toDate=2026-09-07"
```

Refresh the cached summaries for everyone (payroll does this automatically, but
it is useful before reporting):

```bash
curl -X POST "http://localhost:8080/api/attendance/summaries/refresh?month=2026-09"
```

Attendance is always recomputed from punches and roster - it is never edited
directly, so it cannot go stale or be tampered with.

---

## 7. Leave

### Balances

Quotas are seeded on first read: casual 12, sick 8, LWP unlimited/unpaid.

```bash
curl "http://localhost:8080/api/leave-balances/EMP001?year=2026"
```

Override a quota:

```bash
curl -X PUT "http://localhost:8080/api/leave-balances/EMP001?year=2026&leaveType=CASUAL_LEAVE&quota=15"
```

A quota cannot be set below what the employee has already used.

### The workflow

```
Employee applies -> Supervisor endorses -> HR approves
```

**1. Apply** (returns the leave `id`):

```bash
curl -X POST http://localhost:8080/api/leaves -H 'Content-Type: application/json' -d '{"userId":"EMP001","leaveType":"CASUAL_LEAVE","fromDate":"2026-09-24","toDate":"2026-09-25","duration":"FULL_DAY","reason":"Family function"}'
```

`duration` is `FULL_DAY`, `FIRST_HALF` or `SECOND_HALF`. A half day is only
valid when `fromDate` and `toDate` are the same date.

Rejected up front: overlapping open or approved leave, a range spanning two
calendar years, and insufficient balance - so an approver never hits an empty
quota.

**2. Supervisor endorses:**

```bash
curl -X POST http://localhost:8080/api/leaves/1/supervisor-approve -H 'Content-Type: application/json' -d '{"approverId":"SUP001"}'
```

**3. HR gives final approval** - this is the step that consumes balance:

```bash
curl -X POST http://localhost:8080/api/leaves/1/approve -H 'Content-Type: application/json' -d '{"approverId":"HR001","comments":"Approved"}'
```

**Reject** (balance untouched) or **cancel** (balance restored if it was already
approved):

```bash
curl -X POST http://localhost:8080/api/leaves/1/cancel -H 'Content-Type: application/json' -d '{"approverId":"HR001","comments":"Withdrawn by employee"}'
```

### Queues and calendar

```bash
curl http://localhost:8080/api/leaves/pending/SUP001
```

```bash
curl "http://localhost:8080/api/leaves?status=SUPERVISOR_APPROVED"
```

```bash
curl "http://localhost:8080/api/leaves/calendar?fromDate=2026-09-01&toDate=2026-09-30"
```

Clear every pending request before running payroll - only `APPROVED` paid leave
offsets LOP.

---

## 8. Run payroll

### One employee

```bash
curl -X POST http://localhost:8080/api/payroll/generate -H 'Content-Type: application/json' -d '{"employeeId":"EMP001","month":9,"year":2026,"advanceDeduction":1000,"loanDeduction":0,"tds":0,"canteen":300,"bonus":0,"incentive":0,"generatedBy":"HR001"}'
```

You supply **only** the manual amounts. Everything else - attendance, leave,
LOP, proration, PF, ESIC, professional tax, overtime and net pay - is derived.

### Everyone at once

Skips anyone whose period is already generated, so it is safe to re-run.

```bash
curl -X POST "http://localhost:8080/api/payroll/generate-all?month=9&year=2026&generatedBy=HR001"
```

Note this uses zero for all manual deductions. If someone has an advance or
canteen amount, generate them individually.

### Reading the result

| Field | Meaning |
|---|---|
| `workingDays` / `presentDays` / `paidLeaveDays` | Attendance the pay is based on |
| `lopDays` | `workingDays - presentDays - paidLeaveDays` |
| `payableDays` | Days actually paid |
| `earnBasicDA`, `earnHra`, ... | Each component prorated by payable days |
| `otAllowance` | Overtime hours x per-hour rate x multiplier |
| `totalEarnings` | Earnings + bonus + incentive + overtime |
| `pf` vs `pfDeduction` | Full-month PF (informational) vs what is actually deducted |
| `lopDeduction` | **Shown for transparency, not added to the total** |
| `totalDeduction` | PF + ESIC + PT + TDS + advance + loan + canteen |
| `netSalary` | `totalEarnings - totalDeduction` |

`lopDeduction` is not subtracted because the earnings were already prorated down
by the same days - counting it again would deduct twice.

### Correcting a month

Running `/generate` twice returns **409** on purpose. To correct a period, use
regenerate - it writes a new revision and marks the old one `SUPERSEDED` rather
than overwriting, so what you already paid stays reproducible:

```bash
curl -X POST http://localhost:8080/api/payroll/regenerate -H 'Content-Type: application/json' -d '{"employeeId":"EMP001","month":9,"year":2026,"advanceDeduction":1000,"canteen":300,"generatedBy":"HR001"}'
```

See the audit trail:

```bash
curl "http://localhost:8080/api/payroll/employee/EMP001/revisions?month=9&year=2026"
```

Every payroll row snapshots the salary structure, the rule percentages and the
attendance figures at generation time - so raising someone's salary today never
changes last month's payslip.

### Other reads

```bash
curl "http://localhost:8080/api/payroll?month=9&year=2026"
```

```bash
curl http://localhost:8080/api/payroll/employee/EMP001
```

---

## 9. Salary slips

### Print-ready

Open in a browser, then print or save as PDF:

```
http://localhost:8080/api/salary-slips/EMP001/print?month=9&year=2026
```

### JSON

```bash
curl "http://localhost:8080/api/salary-slips/EMP001?month=9&year=2026"
```

Includes the attendance summary, itemised earnings and deductions, net pay and
the amount in words (Indian numbering - lakh/crore).

### CSV for the whole month

```bash
curl -OJ "http://localhost:8080/api/salary-slips/export?month=9&year=2026"
```

Slips are read straight from the payroll snapshot - nothing is recalculated, so
reprinting an old month always gives the same figures.

---

## 10. Reports and dashboard

```bash
curl "http://localhost:8080/api/dashboard?asOf=2026-09-15"
```

Cards: total employees, present today, absent today, on leave, pending leave
requests, unscheduled tomorrow, payroll generated this month, upcoming birthdays
and work anniversaries. Charts: 14-day attendance trend, department strength,
6-month payroll cost, leave usage by type.

| Report | Endpoint |
|---|---|
| Employee master | `GET /api/reports/employees` |
| Monthly attendance | `GET /api/reports/attendance/monthly?month=2026-09` |
| Late coming | `GET /api/reports/attendance/late-coming?month=2026-09` |
| Absent | `GET /api/reports/attendance/absent?month=2026-09` |
| Overtime | `GET /api/reports/attendance/overtime?month=2026-09` |
| LOP | `GET /api/reports/attendance/lop?month=2026-09` |
| Leave balances | `GET /api/reports/leave-balances?year=2026` |
| Payroll | `GET /api/reports/payroll?month=9&year=2026` |
| Payroll by department | `GET /api/reports/payroll/by-department?month=9&year=2026` |
| Payroll by company | `GET /api/reports/payroll/by-company?month=9&year=2026` |
| PF | `GET /api/reports/statutory/pf?month=9&year=2026` |
| Professional tax | `GET /api/reports/statutory/professional-tax?month=9&year=2026` |
| ESIC | `GET /api/reports/statutory/esic?month=9&year=2026` |

Reports aggregate what attendance and payroll already recorded - they never
recalculate, so a report can never disagree with a salary slip.

Attendance reports use `month=yyyy-MM`; payroll reports use separate `month` and
`year` numbers.

---

## 11. Full API reference

| Module | Base path |
|---|---|
| Companies | `/api/companies` |
| Departments | `/api/departments` |
| Designations | `/api/designations` |
| Employees | `/api/employees` |
| Shift master | `/api/shifts` |
| Shift scheduling | `/api/shift-schedules` |
| Attendance | `/api/attendance` |
| Holidays | `/api/holidays` |
| Leave | `/api/leaves` |
| Leave balances | `/api/leave-balances` |
| Salary rules | `/api/salary-rules` |
| Payroll | `/api/payroll` |
| Salary slips | `/api/salary-slips` |
| Reports | `/api/reports` |
| Dashboard | `/api/dashboard` |

Masters follow standard REST: `POST` create, `PUT /{id}` update, `GET /{id}`,
`GET` list, `DELETE /{id}`.

### Error format

Every failure returns the same shape:

```json
{
  "timestamp": "2026-08-03T11:24:17.874335Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Supervisor SUP001 does not manage employee HR001",
  "path": "/api/shift-schedules"
}
```

| Status | Means |
|---|---|
| `400` | Validation failure or a broken business rule - the message says which |
| `404` | Referenced record does not exist |
| `409` | Duplicate code, or the payroll period is already generated |

---

## 12. Troubleshooting

**Everyone shows as absent, whole month is LOP**
No shift roster for that month. Assign shifts, then re-read attendance.

**An employee shows zero attendance while others are fine**
Their `userId` does not match the `user_id` the device writes. Check with:

```bash
mysql -uroot -proot alsama -e "SELECT DISTINCT user_id FROM device_logs;"
```

**`invalidPunches` is high**
People are badging once. Those days are flagged rather than silently counted as
absent - fix the punches in `device_logs`, then re-read attendance.

If it is specifically **long overtime days** being flagged, the exit punch fell
outside the shift's `overtimeWindowMinutes` (default 4 hours past the scheduled
end). Raise it on that shift and re-read attendance.

**Approved leave still shows as LOP**
It is only `SUPERVISOR_APPROVED`, not `APPROVED`. Only final HR approval counts.
Also check the leave falls on scheduled working days - leave on a weekly off or
holiday is not consumed and does not offset LOP.

**`409 Payroll already generated`**
Use `/api/payroll/regenerate` instead of `/generate`.

**Salary breakup looks wrong after changing the salary rule**
The breakup is computed at write time. Re-save the employee (`PUT
/api/employees/{id}`) to recalculate with the new percentages.

**Overtime hours are recorded but nothing is paid**
The employee has `overtimeEligible: false`.

**Sundays are being counted as absent**
The roster was created without `weekOffDays`. Re-run the bulk assignment with
`"weekOffDays":["SUNDAY"]` and `"overwriteExisting":true`.

**Holidays are being counted as working days**
Either the holiday is not in `/api/holidays`, or it is marked
`optionalHoliday: true` (optional holidays stay working days by design).

---

## 13. Before going live

**All endpoints are unauthenticated.** Roles are enforced inside the business
rules - only HR or admin can give final leave approval, and a supervisor can only
touch their own team - but nothing stops an unauthenticated caller from claiming
to be `HR001`. Keep this on a trusted network until authentication is added.

Also worth doing before real use:

- Set `hrms.seed.enabled=false` and remove the demo employees.
- Change the MySQL credentials in `application.properties`.
- Move off `ddl-auto=update` to managed migrations.

JWT/Spring Security, notifications, email, PDF/Excel rendering and audit logs are
listed as future enhancements in the specification and are not built - see
[ARCHITECTURE.md](ARCHITECTURE.md).
