# Accusharp HRMS - User Guide

A Human Resource Management System covering the full employee lifecycle: org
masters, supervisor mapping, shift scheduling, biometric attendance, leave,
loss of pay, payroll, salary slips, reports and a dashboard.

| Doc | Read it when |
|---|---|
| **[WALKTHROUGH.md](WALKTHROUGH.md)** | **Start here.** One complete cycle for one employee, explained |
| **[TESTING.md](TESTING.md)** | You want every URL, header, body and response to test in Postman + SQL |
| **README.md** (this file) | Day-to-day reference |
| **[Attendance.md](Attendance.md)** | The full attendance engine: rules, punch windows, generation, corrections, locking |
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | How it is built and why |
| **[SECURITY.md](SECURITY.md)** | Login, JWT, password hashing, what is and isn't protected yet |

Ready-to-use test assets: a [Postman collection](docs/testing/Accusharp-HRMS.postman_collection.json)
(71 requests, 10 ordered folders), [punch SQL](docs/testing/device_logs_EMP005_2026-09.sql)
and a [missing-punch scenario](docs/testing/device_logs_EMP005_missing_out_punch.sql).

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
10. [Reports and dashboard](#10-reports-and-dashboard) (incl. [contractor reports](#101-contractor-attendance-reports))
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
| Shifts | `MORNING` 06:00-19:00, `GENERAL` 09:00-19:00, `EVENING` 14:00-23:00, `NIGHT` 18:00-08:00 - all with `workingHours` 8, `breakMinutes` 0, `graceMinutes` 120, `overtimeWindowMinutes` 240 |
| Categories | `WORKER`, `STAFF`, `SUPERVISOR`, `MANAGER`, `DIRECTOR` - shared defaults; add more via `/api/categories` |
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
curl -X PUT http://localhost:8080/api/salary-rules -H 'Content-Type: application/json' -d '{"basicDaPercent":50,"basicDaMinimumThreshold":0,"hraPercent":40,"conveyancePercent":10,"educationPercent":10,"pfPercent":12,"esicPercent":0.75,"esicWageCeiling":21000,"ptUpperThreshold":10001,"ptUpperAmount":200,"ptLowerThreshold":7501,"ptLowerAmount":175,"dayWiseDaysInMonth":26,"standardHoursPerDay":8,"overtimeRateMultiplier":1.00,"mlwfAmount":0}'
```

| Field | Meaning |
|---|---|
| `basicDaPercent` | Basic + DA as a share of gross salary |
| `basicDaMinimumThreshold` | Government-notified minimum Basic+DA - wins over the percentage when the percentage lands below it (HRA/conveyance/education still derive from whichever value was used). `0` disables the floor. Revise this whenever the state notifies a new minimum wage. |
| `hraPercent`, `conveyancePercent`, `educationPercent` | Shares of **basic**, not gross |
| `pfPercent` | PF rate, applied to the prorated PF basic |
| `esicPercent` / `esicWageCeiling` | ESIC rate; no ESIC above the ceiling |
| `pt*Threshold` / `pt*Amount` | Professional tax slab on gross salary |
| `dayWiseDaysInMonth` | Payable-day base for `DAY_WISE` staff (26 by convention) |
| `standardHoursPerDay` | Divisor for the per-hour overtime rate |
| `overtimeRateMultiplier` | `1.00` = plain rate, `1.50` = time-and-a-half |
| `mlwfAmount` | Flat Labour Welfare Fund amount deducted from the employee in June and December only, `0` every other month. `0` disables it. |

### 3.2 Company

```bash
curl -X POST http://localhost:8080/api/companies -H 'Content-Type: application/json' -d '{"companyCode":"ACC","companyName":"Accusharp Industries","address":"Pune, Maharashtra","phone":"020-00000000","email":"hr@accusharp.example","status":"ACTIVE"}'
```

### 3.3 Departments, designations and categories

```bash
curl -X POST http://localhost:8080/api/departments -H 'Content-Type: application/json' -d '{"departmentCode":"PROD","departmentName":"Production","description":"Shop floor"}'
```

```bash
curl -X POST http://localhost:8080/api/designations -H 'Content-Type: application/json' -d '{"designationCode":"OPR","designationName":"Machine Operator"}'
```

`Category` is the employee grade - Worker, Supervisor, Manager, Director, or
whatever else a company needs. Five common ones (`WORKER`, `STAFF`,
`SUPERVISOR`, `MANAGER`, `DIRECTOR`) are seeded as shared defaults; add more
the same way as a department or designation:

```bash
curl -X POST http://localhost:8080/api/categories -H 'Content-Type: application/json' -d '{"categoryCode":"TEAM_LEAD","categoryName":"Team Lead"}'
```

Note the `id` each returns - you need them for employees. `categoryId` on an
employee is optional, unlike `departmentId`/`designationId`.

### 3.4 Employees

Create senior people first, so juniors can point at them as supervisor.

```bash
curl -X POST http://localhost:8080/api/employees -H 'Content-Type: application/json' -d '{"userId":"EMP003","employeeCode":"EMP-003","employeeName":"Ravi Deshmukh","companyId":1,"departmentId":1,"designationId":1,"supervisorUserId":"SUP001","joiningDate":"2024-05-01","dateOfBirth":"1995-08-14","status":"PERMANENT","role":"EMPLOYEE","email":"ravi@accusharp.example","phone":"9876543210","grossSalary":24000,"pfBasic":9000,"medicalAllowance":1250,"otherAllowance":0,"overtimeEligible":true}'
```

**`userId` must equal the biometric device's user id.** That single field is what
joins the device, attendance, leave and payroll together. Get it wrong and the
employee will show zero attendance forever.

**`categoryId`, `gender`, `uanNo`, `esicIpNo`, `bankAccountNo` and
`bankIfscNo` are all optional** and may be left out entirely - unlike
`departmentId`/`designationId`, nothing else derives from them.

**The response is `{"employee": {...}, "temporaryPassword": "..."}`, not a bare
employee** - `temporaryPassword` is generated server-side and returned exactly
once; capture it now and relay it to the new hire out of band. If it's lost,
`POST /api/employees/{id}/reset-password` (ADMIN/HR) generates a new one -
the practical stand-in for self-service forgot-password, which this app
can't build without email delivery infrastructure it doesn't have. See
[SECURITY.md](SECURITY.md) for the same one-time-password contract on
`POST /api/companies/onboard`.

You send only these money fields:

| You send | Server derives |
|---|---|
| `grossSalary`, `pfBasic`, `medicalAllowance`, `otherAllowance` | `basicDA`, `hra`, `conveyanceAllowance`, `educationAllowance`, `grossSalaryWage` |

`grossSalaryWage` is never accepted from the API - it is always the sum of
the six components, computed server-side. `basicDA`/`hra`/
`conveyanceAllowance`/`educationAllowance` normally derive the same way, but
you may supply all four of them directly in the same create request instead
- useful when onboarding employees whose exact breakup is already known from
an existing payroll system, so the numbers you already trust are not
recalculated. Supplying all four marks the employee overridden, exactly like
[§3.4.1](#341-manual-salary-structure-override-and-regeneration)'s `PUT
.../salary-structure`; supplying only some of them is rejected - a structure
that is part typed, part rule-derived is not a fixed structure. Leave all
four blank (as above) to keep letting the rule derive them.

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

#### 3.4.1 Manual salary structure override and regeneration

`basicDA`/`hra`/`conveyanceAllowance`/`educationAllowance` are normally
derived, but a real payslip sometimes needs to differ from what the formula
gives. Override them by hand:

```bash
curl -X PUT http://localhost:8080/api/employees/3/salary-structure -H 'Content-Type: application/json' -d '{"basicDA":12500,"hra":5200,"conveyanceAllowance":1100,"educationAllowance":1100}'
```

This marks the employee as overridden - `grossSalaryWage` still refreshes
(so a later change to `medicalAllowance`/`otherAllowance` is reflected), but
the four components above no longer move, even across a plain `PUT
/api/employees/{id}` or a `SalaryRule` change.

To go back to rule-derived values - after correcting the override, or after
a `SalaryRule` change you actually want applied:

```bash
curl -X POST http://localhost:8080/api/employees/3/salary-structure/regenerate
```

or for every non-overridden employee in the company at once:

```bash
curl -X POST http://localhost:8080/api/employees/salary-structure/regenerate-all
```

Employees currently overridden are skipped by the bulk call, so it never
silently discards a deliberate manual value - regenerate those individually
if that's really what you want. Neither call touches payroll history; run
`/api/payroll/regenerate` for any already-generated period afterwards to
pick up the corrected structure.

#### 3.4.2 Bulk import from CSV

Onboarding more than a handful of people through the frontend's CSV upload
screen goes through one call instead of one `POST /api/employees` per row:

```bash
curl -X POST http://localhost:8080/api/employees/bulk-import -H "Authorization: Bearer $TOKEN" -F "file=@employees.csv;type=text/csv"
```

CSV header (case-insensitive, any column order):

```
userId,employeeCode,employeeName,companyId,departmentId,designationId,categoryId,supervisorUserId,joiningDate,dateOfBirth,gender,status,recordStatus,role,email,phone,uanNo,esicIpNo,bankAccountNo,bankIfscNo,grossSalary,pfBasic,medicalAllowance,otherAllowance,overtimeEligible,basicDA,hra,conveyanceAllowance,educationAllowance
```

Only `userId`, `employeeCode`, `employeeName`, `status`, `grossSalary`,
`pfBasic`, `medicalAllowance` and `otherAllowance` are required; everything
else may be left blank - including `categoryId`, `gender`, `uanNo`,
`esicIpNo`, `bankAccountNo` and `bankIfscNo`. `companyId` is ignored for a company-scoped caller -
same as a single create, the caller's own company always wins. Dates are
`yyyy-MM-dd`. The last four columns are the same optional structure-override
fields described above - fill in all four on a row to use those exact
values instead of deriving them, leave all four blank to derive as usual,
or filling in only some of them fails that row (see §3.4).

Numeric columns (`grossSalary`, `pfBasic`, `medicalAllowance`,
`otherAllowance`, `basicDA`, `hra`, `conveyanceAllowance`,
`educationAllowance`, plus the `Long` columns `companyId`/`departmentId`/
`designationId`) tolerate Excel-style formatting - thousands separators,
`₹`/`$` symbols, and stray whitespace are stripped before parsing, so
`"41,000.00"` and `"₹ 41,000.00"` both parse fine. Only genuinely
non-numeric text fails.

The frontend's downloadable template (a formatted `.xlsx`, not a plain CSV)
puts a title, instructions and a legend above the real header row for
readability, with a trailing `" *"` marked on every required column header.
Since Excel's *Save As → CSV* carries those decorative rows into the file
unchanged, the parser doesn't assume the header is line 1: it scans for the
row containing a known column name and strips the `" *"` marker, so the
template can be filled in and uploaded as-is without deleting anything by
hand first.

Every row is attempted independently through the exact same path as a single
`POST /api/employees` - same admin-escalation guard, same one-time temporary
password per row. **One bad row (a duplicate code, a typo'd number) fails
only that row**; the response tells you exactly which:

```json
{
  "totalRows": 4,
  "successCount": 2,
  "failureCount": 2,
  "succeeded": [ { "employee": { "userId": "EMP101", ... }, "temporaryPassword": "..." }, ... ],
  "errors": [
    { "rowNumber": 3, "identifier": "EMP103", "message": "Employee already exists with code EMP-103" },
    { "rowNumber": 4, "identifier": null, "message": "grossSalary must be a number, got 'notanumber'" }
  ]
}
```

Capture every `temporaryPassword` in `succeeded` now, same as a single
create - it is never shown again.

**For a real batch (tens to hundreds of rows), reading passwords out of that
JSON one by one does not scale** - there is no email/SMS infrastructure in
this app to deliver them automatically (see [SECURITY.md](SECURITY.md)).
Add `?format=csv` to get a downloadable credentials sheet instead, built
from the exact same run:

```bash
curl -X POST "http://localhost:8080/api/employees/bulk-import?format=csv" -H "Authorization: Bearer $TOKEN" -F "file=@employees.csv;type=text/csv" -o credentials.csv
```

Returns `text/csv` (`userId,employeeCode,employeeName,temporaryPassword`,
one row per employee actually created - failed rows are not in it) instead
of the JSON body above. Same one-time-return contract: nothing here is
persisted or retrievable a second time, so download it now.

#### 3.4.3 Salary revision (hike, promotion, correction)

A gross-salary change is not a plain `PUT /api/employees/{id}` - that would
silently overwrite the old figure with no record of what it was, when it
changed, or why. Use the dedicated endpoint instead:

```bash
curl -X POST http://localhost:8080/api/employees/3/salary-revision -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"newGrossSalary":28000,"effectiveDate":"2026-09-01","reason":"ANNUAL_INCREMENT","remarks":"Yearly appraisal"}'
```

`reason` is one of `ANNUAL_INCREMENT`, `PROMOTION`, `MARKET_CORRECTION`,
`OTHER`. This updates `grossSalary` and re-derives `basicDA`/`hra`/
`conveyanceAllowance`/`educationAllowance` from the company's current
`SalaryRule`, same as a normal create. **If the employee is currently
overridden** (§3.4.1), re-deriving is not possible - a frozen structure
never follows `grossSalary` on its own - so the same request must also
carry the four replacement values:

```bash
curl -X POST http://localhost:8080/api/employees/3/salary-revision -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"newGrossSalary":28000,"effectiveDate":"2026-09-01","reason":"PROMOTION","basicDA":13500,"hra":5800,"conveyanceAllowance":1100,"educationAllowance":1100}'
```

Every revision is recorded, not just applied - `previousGrossSalary`,
`newGrossSalary`, the computed `hikePercent`, `effectiveDate`, `reason` and
who applied it. Pull the full history for an employee:

```bash
curl http://localhost:8080/api/employees/3/salary-revisions -H "Authorization: Bearer $TOKEN"
```

Newest `effectiveDate` first. This is the audit trail for "what was this
person paid before, and when did it change" - something a plain salary
update alone can never answer after the fact.

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

Roster everyone. An unscheduled day still generates - blank, and `ABSENT`, so
the gap is visible instead of silently costing nobody anything - but a blank
`ABSENT` day is loss of pay, and only the roster can make it a real one.

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

### Bulk assignment (different shift per employee)

The call above puts every listed employee on the *same* shift. When the team
needs different employees on different shifts (or different days) in one
roster upload, each entry carries its own `userId`/`shiftDate`/`shiftCode`:

```bash
curl -X POST http://localhost:8080/api/shift-schedules/bulk/varied -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"assignments":[{"userId":"EMP001","shiftDate":"2026-09-01","shiftCode":"MORNING","weekOff":false},{"userId":"EMP003","shiftDate":"2026-09-01","shiftCode":"NIGHT","weekOff":false}]}'
```

Or the same thing from the frontend's CSV upload
(`userId,shiftDate,shiftCode,weekOff`):

```bash
curl -X POST http://localhost:8080/api/shift-schedules/bulk/csv -H "Authorization: Bearer $TOKEN" -F "file=@roster.csv;type=text/csv"
```

Both return the same `{totalRows, successCount, failureCount, succeeded,
errors}` shape as the employee CSV import above - an unknown employee or an
unknown shift code fails only that one entry.

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

- Punches are matched to the scheduled shift's window: `entryWindowBufferMinutes`
  **before** the start (people badge in early, 60 by default) and up to
  `overtimeWindowMinutes` **after** the scheduled end (people stay late). The
  window is deliberately not symmetric - a late exit is overtime, not a missing
  punch.
- **First punch = in, last punch = out.**
- With 4+ punches, the gaps in the middle are treated as the real break.
  Otherwise the shift's configured `breakMinutes` is used.
- Worked time = span between first and last punch, minus break.
- `fullDayThresholdPercent` of the shift = full day (75% by default),
  `halfDayThresholdPercent` = half day (40% by default), below that = absent.
- **One lone punch** is flagged `INVALID_PUNCH` - a device or user error, not an
  absence. These need fixing before payroll.

The three configurable numbers above (`entryWindowBufferMinutes`,
`fullDayThresholdPercent`, `halfDayThresholdPercent`) live on `AttendanceRule`,
company-scoped exactly like `SalaryRule` (§3.1) - a company without its own
customized rule falls back to the global default, which is what every company
used before this was configurable:

```bash
curl http://localhost:8080/api/attendance-rules
curl -X PUT http://localhost:8080/api/attendance-rules -H 'Content-Type: application/json' \
  -d '{"entryWindowBufferMinutes":60,"fullDayThresholdPercent":75,"halfDayThresholdPercent":40}'
```

`halfDayThresholdPercent` must be less than `fullDayThresholdPercent`. A
change only affects attendance generated or regenerated afterward - see
"Generate the attendance payroll will be paid from" below.

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

### Generate the attendance payroll will be paid from

The calls above are a **preview** - they compute from punches and persist
nothing. Before payroll can run, the month has to be generated and reviewed:

```bash
curl -X POST http://localhost:8080/api/attendance/generate -H 'Content-Type: application/json' -d '{"month":"2026-09","userIds":["EMP001"],"generatedBy":"HR001"}'
```

Omit `userIds` to run the whole company. This writes one row per rostered day.
Review them, provenance included:

```bash
curl "http://localhost:8080/api/attendance/EMP001/records?month=2026-09"
```

Devices miss punches. When one does, the day reads as `INVALID_PUNCH` and would
silently become loss of pay - so HR can correct it, either by supplying the
times the device missed or by declaring the day outright:

```bash
curl -X PUT http://localhost:8080/api/attendance/EMP001/2026-09-25 -H 'Content-Type: application/json' -d '{"firstIn":"2026-09-25T06:00:00","lastOut":"2026-09-25T15:00:00","remarks":"Device missed the exit punch","updatedBy":"HR001"}'
```

The row becomes `MANUAL` and **survives the next generation run** - rerun
generation freely to pick up late-arriving punches without losing corrections.
Pass `"overwriteManual": true` only when you deliberately want to discard them.

Resync the cached summaries from the stored days before reporting:

```bash
curl -X POST "http://localhost:8080/api/attendance/summaries/refresh?month=2026-09"
```

Punches themselves stay read-only: a correction is recorded on the generated day
next to the original device reading, never by rewriting `device_logs`.

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

### HR entering an already-approved leave directly

For backfilling a day that already happened - an employee took time off
informally and HR wants attendance/payroll to reflect it - not for a
forward-looking request. Skips apply and supervisor-endorsement entirely:
the leave is created `APPROVED` immediately and consumes balance the same
moment, same hard-block on insufficient balance as the normal flow:

```bash
curl -X POST http://localhost:8080/api/leaves/hr-create -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"userId":"EMP001","leaveType":"CASUAL_LEAVE","fromDate":"2026-09-24","toDate":"2026-09-24","duration":"FULL_DAY","reason":"Backfilling an informal day off"}'
```

Requires `LEAVE_APPROVE` - the same trust bar as final approval, since this
*is* an approval, just without a preceding request to approve. The response
carries `"origin":"HR_DIRECT"` (`"origin":"SELF_SERVICE"` for a leave that
went through the normal chain) so reports and history can tell the two
apart even once both sit at `APPROVED`.

**Bulk CSV variant**, for backfilling several employees/periods at once -
same independently-failable-row contract as `/api/employees/bulk-import`:

```bash
curl -X POST http://localhost:8080/api/leaves/bulk-import -H "Authorization: Bearer $TOKEN" -F "file=@leaves.csv;type=text/csv"
```

CSV header (case-insensitive, any column order):

```
userId,leaveType,fromDate,toDate,duration,reason
```

Only `userId`, `leaveType`, `fromDate` and `toDate` are required; `duration`
defaults to `FULL_DAY` when blank, `reason` is optional. A row that fails
(bad date, unknown leave type, overlapping leave, insufficient balance) is
reported in `errors` and never blocks the rest of the file - identical
`{totalRows, successCount, failureCount, succeeded, errors}` shape as every
other bulk/CSV endpoint.

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

Payroll pays from the attendance you generated in step 7 and **refuses to run
if the period was never generated** - nobody gets paid off numbers nobody
reviewed. Generating also locks the month, so the slip stays reproducible.

### One employee

```bash
curl -X POST http://localhost:8080/api/payroll/generate -H 'Content-Type: application/json' -d '{"employeeId":"EMP001","month":9,"year":2026,"advanceDeduction":1000,"loanDeduction":0,"tds":0,"canteen":300,"bonus":0,"incentive":0,"generatedBy":"HR001"}'
```

To correct attendance after payroll has run: unlock, fix, regenerate.

```bash
curl -X POST "http://localhost:8080/api/attendance/EMP001/unlock?month=2026-09&actorId=HR001"
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

### Bulk generate from CSV, with per-employee amounts

`/generate-all` above is all-or-nothing on the manual amounts (always zero).
When bonus/incentive/advance/canteen differ per employee for the month, upload
a CSV instead - one row per employee, `month`/`year` apply to the whole file:

```bash
curl -X POST "http://localhost:8080/api/payroll/bulk-generate?month=9&year=2026" -H "Authorization: Bearer $TOKEN" -F "file=@payroll.csv;type=text/csv"
```

CSV header (only `employeeId` is required, every amount defaults to `0`):

```
employeeId,bonus,incentive,tds,advanceDeduction,loanDeduction,canteen
```

Same per-row behavior as the other bulk/CSV endpoints: an employee with no
attendance generated for the period, or one already paid this month, fails
only that row (`{"totalRows":..., "succeeded":[...], "errors":[...]}`).
Add `&regenerate=true` to recompute rows that are already generated as a new
revision instead of erroring them - the same choice `/regenerate` gives a
single employee. The amount columns tolerate Excel-style formatting the same
way the employee import does (§3.4.2) - thousands separators, `₹`/`$`, and
stray whitespace are stripped before parsing.

### Reading the result

| Field | Meaning |
|---|---|
| `workingDays` / `presentDays` / `paidLeaveDays` | Attendance the pay is based on. `presentDays` is capped at `dayWiseDaysInMonth` for `DAY_WISE` (matching `payableDays` below), even if attendance recorded more - a worker with no weekly off at all can be present more days than one standard month |
| `lopDays` | `workingDays - presentDays - paidLeaveDays` (not applicable to `DAY_WISE` - attendance *is* the pay there) |
| `payableDays` | Days actually paid. `DAY_WISE`: `presentDays` alone, capped at `dayWiseDaysInMonth` - paid leave earns no share of the fixed structure (only its own overtime credit, see `overtimeHours` below). Everyone else: working days minus LOP |
| `earnBasicDA`, `earnHra`, ... | Each component prorated by payable days |
| `overtimeHours` | `PERMANENT`/`CONTRACT`/`INTERN`: sum of each day's own excess over its shift. `DAY_WISE`: `max(0, totalHours - min(presentDays, dayWiseDaysInMonth) x standardHoursPerDay)` **plus** `paidLeaveDays x standardHoursPerDay` added on top - approved paid leave always contributes its own overtime hours, never absorbed by the present-days cap |
| `otAllowance` | `overtimeHours` x per-hour rate x multiplier |
| `totalEarnings` | Earnings + bonus + incentive + overtime |
| `pf` vs `pfDeduction` | Full-month PF (informational) vs what is actually deducted |
| `esic` | 0.75% (configurable) of earned `basicDA` - **not** the full earned gross - while it stays at or under `esicWageCeiling`; zero above it |
| `lopDeduction` | **Shown for transparency, not added to the total** |
| `mlwf` | Labour Welfare Fund - non-zero only in the June and December payroll run |
| `totalDeduction` | PF + ESIC + PT + MLWF + TDS + advance + loan + canteen |
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

### Debugging a wrong number

`GET /api/payroll?month=&year=` returns the stored `Payroll` rows as-is - the
full snapshot, but only what was true at generation time. When a net salary
looks wrong and you need to see *why* without recomputing by hand:

```bash
curl "http://localhost:8080/api/payroll/debug?month=9&year=2026" -H "Authorization: Bearer $TOKEN"
```

Same scope as the call above, but every row also carries a live re-read of
the employee's current `grossSalary`/`pfBasic` and the company's current
`SalaryRule` percentages next to what was actually stored, plus two flags:

| Field | True means |
|---|---|
| `masterDataDrifted` | The employee's salary/PF-basic changed *after* this payroll was generated |
| `ruleDrifted` | The company's `SalaryRule` percentages changed *after* this payroll was generated |

A `true` on either explains "why does this month look different" better than
re-deriving the calculation - the payroll was correct for the data it was
computed from, that data has since moved.

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

Every report above covers **your own employees only**. A labour contractor's
workers are excluded from all of them - including the PF, ESIC and
professional-tax returns, which is the point: you do not file statutory
returns for somebody else's staff. Their attendance has its own reports below.

### 10.1 Contractor attendance reports

The whole point of registering a contractor's workers is to hand the
contractor a defensible attendance sheet they can run their own payroll from.

```bash
# The month's sheet for one contractor: a cover line plus one row per worker
curl "http://localhost:8080/api/contractors/2/reports/attendance/monthly?month=2026-09"

# The same thing as a CSV to send them - the contractor's totals are appended
# below the rows, so the figure they invoice against travels in the same file
curl -OJ "http://localhost:8080/api/contractors/2/reports/attendance/monthly/export?month=2026-09"

# The day-by-day register behind those totals, for when a figure is queried
curl "http://localhost:8080/api/contractors/2/reports/attendance/daily?from=2026-09-01&to=2026-09-30"

# Every contractor's month, one line each - the side-by-side view when you
# have more than one agency on site
curl "http://localhost:8080/api/contractors/reports/attendance/summary?month=2026-09"
```

| Report | Endpoint |
|---|---|
| One contractor's month | `GET /api/contractors/{id}/reports/attendance/monthly?month=2026-09` |
| ... as CSV | `GET /api/contractors/{id}/reports/attendance/monthly/export?month=2026-09` |
| Daily register | `GET /api/contractors/{id}/reports/attendance/daily?from=&to=` |
| ... as CSV | `GET /api/contractors/{id}/reports/attendance/daily/export?from=&to=` |
| All contractors, one line each | `GET /api/contractors/reports/attendance/summary?month=2026-09` |
| ... as CSV | `GET /api/contractors/reports/attendance/summary/export?month=2026-09` |

Two figures on these are worth knowing before you send one out:

- **`workersWithoutAttendance`** on the summary line counts workers with no
  generated attendance for the period at all. Anything above zero means the
  report is not ready - generate it first, or those people read as having
  worked nothing.
- **`recordStatus`** on each day of the register is `GENERATED` or `MANUAL`.
  A day somebody corrected by hand is disclosed as such rather than presented
  as a device reading, which is the difference between a report that survives
  a dispute and one that does not.

The order for a contractor is the same as for your own staff, minus payroll:
onboard the contractor, add their workers, roster them, generate, report.

```bash
# 1. Onboard the contractor
curl -X POST http://localhost:8080/api/contractors -H 'Content-Type: application/json' -d '{
  "contractorCode": "ACME", "contractorName": "Acme Manpower Services",
  "contactPerson": "Sanjay Kale", "email": "sanjay@acmemanpower.example"
}'

# 2. Add a worker. Identity and a supervisor of yours - nothing else. There is
#    deliberately no salary field: you do not pay these people.
curl -X POST http://localhost:8080/api/contractors/2/employees -H 'Content-Type: application/json' -d '{
  "userId": "ACM001", "employeeCode": "AC-001", "employeeName": "Ravi Kumar",
  "supervisorUserId": "SUP001", "joiningDate": "2026-08-01"
}'

# 3. Roster them - the ordinary shift-schedule endpoints, on your own shifts
curl -X POST http://localhost:8080/api/shift-schedules/bulk -H 'Content-Type: application/json' -d '{
  "userIds": ["ACM001"], "fromDate": "2026-09-01", "toDate": "2026-09-30",
  "shiftCode": "GENERAL", "weekOffDays": ["SUNDAY"], "skipHolidays": true
}'

# 4. Generate their attendance. Scoped to this contractor - it never touches
#    your own staff, and POST /api/attendance/generate never touches theirs.
curl -X POST "http://localhost:8080/api/contractors/2/attendance/generate?month=2026-09"
```

`includeUnrostered` defaults to **false** here, the opposite of the company
console. A contractor's workers are rostered only for the days they are
actually sent in, so an unrostered day means "not deployed" - marking it
absent would put a dispute on their invoice rather than surface a rostering
gap. Set `?includeUnrostered=true` only if you roster the contractor for every
calendar day.

---

## 11. Full API reference

| Module | Base path |
|---|---|
| Auth (login/refresh/logout/change-password) | `/api/auth` - see [SECURITY.md](SECURITY.md) |
| Companies | `/api/companies` (`POST /onboard` creates the company plus its first admin - see [SECURITY.md](SECURITY.md)) |
| Departments | `/api/departments` |
| Designations | `/api/designations` |
| Categories | `/api/categories` (employee grade - Worker, Supervisor, Manager, Director, ...; company-defined, same pattern as departments/designations) |
| Employees | `/api/employees` (`POST /bulk-import` - CSV bulk onboarding, `?format=csv` for a downloadable credentials sheet; `POST /{id}/salary-revision`, `GET /{id}/salary-revisions` - hike/promotion history). Your own staff only - a labour contractor's workers are never returned here, and never accepted for a write |
| Contractors | `/api/contractors` - labour contractors, the workforce they deploy, that workforce's attendance and the reports sent back to them. See [section 10.1](#101-contractor-attendance-reports) |
| Shift master | `/api/shifts` |
| Shift scheduling | `/api/shift-schedules` (`POST /bulk/varied`, `POST /bulk/csv` - per-employee shift, unlike `/bulk`'s one-shift-for-all) |
| Attendance | `/api/attendance` (`POST /generate`, `GET /{userId}/records`, `PUT /{userId}/{date}`, `POST /{userId}/unlock`) |
| Attendance rules | `/api/attendance-rules` |
| Holidays | `/api/holidays` |
| Leave | `/api/leaves` (`POST /hr-create` - HR-direct already-approved entry; `POST /bulk-import` - its CSV bulk variant) |
| Leave balances | `/api/leave-balances` |
| Salary rules | `/api/salary-rules` |
| Payroll | `/api/payroll` (`POST /bulk-generate` - CSV bulk run; `GET /debug` - full per-employee breakdown with live drift flags) |
| Salary slips | `/api/salary-slips` |
| Reports | `/api/reports` |
| Dashboard | `/api/dashboard` |
| Audit logs | `/api/audit-logs` - ADMIN/platform only, see [SECURITY.md](SECURITY.md) |

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
The breakup is computed at write time, so an existing employee is not
recomputed just because the rule changed. Fix one employee with `POST
/api/employees/{id}/salary-structure/regenerate`, or every non-overridden
employee at once with `POST /api/employees/salary-structure/regenerate-all`.
See §3.4.1.

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

**All `/api/**` endpoints require a bearer token, and each one requires a
specific permission** (see [SECURITY.md](SECURITY.md) for the full matrix) -
log in via `POST /api/auth/login` first. Multi-tenant company isolation,
"view only my own data" self-service scoping, full audit coverage with
retention/export, and dynamic role/permission management are all built now
(Phases 3-10) - see [SECURITY.md](SECURITY.md) for the phase-by-phase
detail and the current, much shorter "Not yet built" list (mainly: a true
self-service password-recovery flow, since there's no email delivery
infrastructure to build it on - an ADMIN/HR-triggered reset exists instead).

Also worth doing before real use:

- Set `hrms.seed.enabled=false` and remove the demo employees.
- Set `DB_USERNAME`/`DB_PASSWORD`/`JWT_SECRET` via environment variables - see
  [SECURITY.md](SECURITY.md). The values in `application.properties` are
  local-development defaults only.
- Move off `ddl-auto=update` to managed migrations.
- If this database predates Phase 6 (`SECURITY.md`), run
  [`docs/migrations/2026-08-08-per-company-masters.sql`](docs/migrations/2026-08-08-per-company-masters.sql)
  by hand once - `ddl-auto=update` adds the new `company_id` columns on its
  own, but cannot drop the old single-column unique index on
  `department`/`designation`/`shift`, so two companies can't share a code
  until that manual step runs.
