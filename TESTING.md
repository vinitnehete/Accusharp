# Testing Guide — every URL, header, body and response

Everything you need to run one full payroll cycle in Postman and MySQL.

**Every response below is real output** captured from an actual run — not
examples written from the spec. If you follow the steps in order you will see
these exact numbers.

**Scenario**: Priya Kulkarni (`EMP005`), machine operator, gross ₹26,000,
September 2026. She works 22 days, does one long overtime day, takes 2 days
approved leave and is absent once without leave.

| File | What it is |
|---|---|
| [`docs/testing/Accusharp-HRMS.postman_collection.json`](docs/testing/Accusharp-HRMS.postman_collection.json) | Import into Postman — 72 requests in 11 ordered folders (starting with login), with assertions |
| [`docs/testing/device_logs_EMP005_2026-09.sql`](docs/testing/device_logs_EMP005_2026-09.sql) | The punches, ready to run against MySQL |
| [`docs/testing/multi-company-smoke-test.sh`](docs/testing/multi-company-smoke-test.sh) | A separate curl-based script proving multi-company isolation and self-service scoping over real HTTP - see SECURITY.md |

**This is a manual/Postman walkthrough of one payroll cycle, not the
automated test suite.** The app also has ~90 JUnit tests
(`./mvnw test`, or `./mvnw test -Dtest=ClassName` for one class) across 13
classes under `src/test/java/com/accusharp/hrms/` - unit tests for the
calculation services (`calculation/`), full-flow integration tests
(`PayrollFlowIntegrationTest`, `AttendanceRegularisationTest`,
`NightShiftMonthBoundaryTest`), and real-HTTP tests spinning up the app on a
random port (`AuthApiHttpTest`, `TenantIsolationHttpTest`,
`SelfServiceScopingHttpTest`, `CompanyOnboardingHttpTest`, `AuditLogHttpTest`,
`AttendanceApiHttpTest`) that log in over the wire the same way this document
does, then drive the API with a real `HttpClient`. Those run on every change
and are the first thing to check if something here stops matching reality;
this document is for a human working through the same cycle by hand.

---

## Contents

- [Headers and auth — the short version](#headers-and-auth--the-short-version)
- [Setup](#setup)
- [Step 0 — Look around](#step-0--look-around)
- [Step 1 — Create a designation](#step-1--create-a-designation)
- [Step 2 — Create the employee](#step-2--create-the-employee)
  - [Manual salary structure override and regenerate](#manual-salary-structure-override-and-regenerate)
- [Step 3 — Create a holiday](#step-3--create-a-holiday)
- [Step 4 — Roster the month](#step-4--roster-the-month)
- [Step 5 — Load punches (DB, not API)](#step-5--load-punches-db-not-api)
- [Step 6 — Attendance BEFORE leave](#step-6--attendance-before-leave)
- [Step 7 — Apply for leave](#step-7--apply-for-leave)
- [Step 8 — Supervisor endorses](#step-8--supervisor-endorses)
- [Step 9 — HR approves](#step-9--hr-approves)
- [Step 10 — Attendance AFTER approval](#step-10--attendance-after-approval)
- [Step 11 — Generate payroll](#step-11--generate-payroll)
- [Step 12 — Salary slip](#step-12--salary-slip)
- [Step 13 — Reports](#step-13--reports)
- [Step 14 — Correct a mistake](#step-14--correct-a-mistake)
- [Error responses](#error-responses)
- [Useful SQL](#useful-sql)
- [Full endpoint list](#full-endpoint-list)

---

## Headers and auth — the short version

This trips people up, so it is worth stating plainly:

| | |
|---|---|
| **Authentication** | **Required on every `/api/**` endpoint except `/api/auth/**`.** `POST /api/auth/login` first, then send the returned `accessToken` as `Authorization: Bearer <token>` on everything else. |
| **Headers on every request** | `Authorization: Bearer <token>` (all methods, including GET/DELETE - a bare `curl http://localhost:8080/api/employees` now gets `401`) |
| **Headers on POST / PUT / PATCH** | Also `Content-Type: application/json` |
| **Response type** | `application/json`, except the print view (`text/html`) and CSV export (`text/csv`) |

In the **Postman collection**, this is already wired up for you: run the
**"00 - Login"** folder first (one request, logs in as the seeded `HR001`
user) - its test script stores the token in the `{{accessToken}}` collection
variable, and collection-level auth means every other request inherits it
automatically. Nothing else in the collection needed to change. The token
expires in 15 minutes; rerun "00 - Login" if requests start failing with 401
partway through a session. If you're issuing requests by hand (`curl`,
a browser REST client), you need to capture and pass the token yourself -
see the login call at the top of Setup below.

In Postman, selecting **Body → raw → JSON** sets `Content-Type` for you
automatically; auth is handled by the collection/folder-level setting above,
not per-request.

### Date formats

| Where | Format | Example |
|---|---|---|
| Dates in bodies and query params | `yyyy-MM-dd` | `2026-09-22` |
| Attendance and shift-planner month | `yyyy-MM` | `month=2026-09` |
| Payroll, slips and payroll reports | two separate numbers | `month=9&year=2026` |
| Times | `HH:mm:ss` | `06:00:00` |

The two different month formats are the single most common source of a
confusing `400`. Attendance uses `month=2026-09`; payroll uses `month=9&year=2026`.

---

## Setup

Start the app:

```bash
cd /Users/vinitnehete/Downloads/Accusharp && ./mvnw spring-boot:run
```

That uses MySQL (`alsama` on `localhost:3306`, `root`/`root`). To try it without
MySQL, use `-Dspring-boot.run.profiles=h2` — but then you cannot use the punch
SQL, since there is no MySQL to insert into.

**Not using Postman?** Log in first and capture the token:

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"HR001","password":"Accusharp@123"}'
# -> {"accessToken": "...", "refreshToken": "...", ...}
```

Pass `-H "Authorization: Bearer <accessToken>"` on every request in every step
below (the Postman collection does this for you automatically - see
"Headers and auth" above).

In Postman: **Import → File →** `docs/testing/Accusharp-HRMS.postman_collection.json`.
Set the `baseUrl` variable if you are not on `http://localhost:8080`. Run
**"00 - Login"** first, then the rest in order (`00 - Look around` through
`09 - Error cases worth seeing`), pausing after folder `02` to load the
punches.

---

## Step 0 — Look around

A fresh database is pre-seeded. Check the salary rule **before** creating anyone
— the salary breakup is calculated at save time, so changing it later will not
touch employees that already exist.

```
GET  http://localhost:8080/api/salary-rules
Headers: Authorization: Bearer <token>
Body:    none
```

**200 OK**

```json
{
  "id": 1,
  "basicDaPercent": 50.0,
  "hraPercent": 40.0,
  "conveyancePercent": 10.0,
  "educationPercent": 10.0,
  "pfPercent": 12.0,
  "esicPercent": 0.75,
  "esicWageCeiling": 21000.0,
  "ptUpperThreshold": 10001.0,
  "ptUpperAmount": 200.0,
  "ptLowerThreshold": 7501.0,
  "ptLowerAmount": 175.0,
  "dayWiseDaysInMonth": 26,
  "standardHoursPerDay": 8.0,
  "overtimeRateMultiplier": 1.0
}
```

```
GET  http://localhost:8080/api/shifts
```

**200 OK** — four seeded shifts. Note `NIGHT` ends *before* it starts, which is
how the system knows it crosses midnight:

```json
[
  { "id": 1, "shiftCode": "MORNING", "startTime": "06:00:00", "endTime": "15:00:00",
    "workingHours": 8, "breakMinutes": 60, "graceMinutes": 15, "overtimeWindowMinutes": 240 },
  { "id": 2, "shiftCode": "GENERAL", "startTime": "09:00:00", "endTime": "18:00:00", "...": "..." },
  { "id": 3, "shiftCode": "EVENING", "startTime": "14:00:00", "endTime": "23:00:00", "...": "..." },
  { "id": 4, "shiftCode": "NIGHT",   "startTime": "18:00:00", "endTime": "08:00:00", "...": "..." }
]
```

```
GET  http://localhost:8080/api/departments
GET  http://localhost:8080/api/designations
```

**200 OK** — **write these ids down**, you need them in step 2:

```json
[{ "id": 1, "departmentCode": "PROD",  "departmentName": "Production" },
 { "id": 2, "departmentCode": "ADMIN", "departmentName": "Administration" }]

[{ "id": 1, "designationCode": "OPR", "designationName": "Machine Operator" },
 { "id": 2, "designationCode": "MGR", "designationName": "Manager" }]
```

```
GET  http://localhost:8080/api/employees
```

Who exists and what they can do:

| userId | Name | Role | Can |
|---|---|---|---|
| `HR001` | Meera Joshi | HR | Approve leave finally, schedule anyone |
| `SUP001` | Rakesh Patil | SUPERVISOR | Endorse leave, schedule **own team only** |
| `EMP001`, `EMP002` | Sunil, Anita | EMPLOYEE | Apply for leave |

---

## Step 1 — Create a designation

```
POST http://localhost:8080/api/designations
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{
  "designationCode": "SR-OPR",
  "designationName": "Senior Operator",
  "description": "Senior grade"
}
```

**201 Created**

```json
{
  "id": 3,
  "designationCode": "SR-OPR",
  "designationName": "Senior Operator",
  "description": "Senior grade"
}
```

`id: 3` is what you pass as `designationId` next.

---

## Step 2 — Create the employee

```
POST http://localhost:8080/api/employees
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{
  "userId": "EMP005",
  "employeeCode": "EMP-005",
  "employeeName": "Priya Kulkarni",
  "companyId": 1,
  "departmentId": 1,
  "designationId": 3,
  "supervisorUserId": "SUP001",
  "joiningDate": "2024-02-12",
  "dateOfBirth": "1996-09-20",
  "status": "PERMANENT",
  "role": "EMPLOYEE",
  "email": "priya@accusharp.example",
  "phone": "9822001122",
  "grossSalary": 26000,
  "pfBasic": 9000,
  "medicalAllowance": 1250,
  "otherAllowance": 0,
  "overtimeEligible": true
}
```

**201 Created**

```json
{
  "employee": {
    "id": 5,
    "userId": "EMP005",
    "employeeCode": "EMP-005",
    "employeeName": "Priya Kulkarni",
    "companyName": "Accusharp Industries",
    "departmentName": "Production",
    "designationName": "Senior Operator",
    "supervisorUserId": "SUP001",
    "supervisorName": "Rakesh Patil",
    "joiningDate": "2024-02-12",
    "dateOfBirth": "1996-09-20",
    "status": "PERMANENT",
    "recordStatus": "ACTIVE",
    "role": "EMPLOYEE",
    "email": "priya@accusharp.example",
    "phone": "9822001122",
    "grossSalary": 26000,
    "pfBasic": 9000,
    "basicDA": 13000.0,
    "hra": 5200.0,
    "conveyanceAllowance": 1300.0,
    "educationAllowance": 1300.0,
    "medicalAllowance": 1250,
    "otherAllowance": 0,
    "grossSalaryWage": 22050.0,
    "overtimeEligible": true
  },
  "temporaryPassword": "Tp7-xxxxxxxxxxxxxxxxxxxxxx"
}
```

**The response wraps the employee, it isn't a bare employee object.** Every
employee HR/ADMIN creates now gets a generated one-time password
(`temporaryPassword`), the only way that employee will ever log in - there is
no forgot-password recovery if it's lost after this response. Capture it now.
This didn't used to be true - see SECURITY.md's Phase 9.

**You sent 4 money fields; 5 came back derived (inside `employee`).**

```
basicDA    = 26000 × 50%  = 13000
hra        = 13000 × 40%  =  5200
conveyance = 13000 × 10%  =  1300
education  = 13000 × 10%  =  1300
                            + medical 1250 + other 0
grossSalaryWage           = 22050
```

`basicDA` and friends are not on the request at all — you cannot send them.

### Field reference

| Field | Required | Notes |
|---|---|---|
| `userId` | **yes** | **Must equal the biometric device's `user_id`.** The key that joins device → attendance → leave → payroll |
| `employeeCode` | **yes** | Unique. Your internal staff number |
| `employeeName` | **yes** | |
| `status` | **yes** | `PERMANENT` / `CONTRACT` / `INTERN` = paid on working days minus LOP. `DAY_WISE` = paid only for days attended, over a fixed 26-day base |
| `grossSalary` | **yes** | Must be > 0 |
| `pfBasic`, `medicalAllowance`, `otherAllowance` | **yes** | May be 0, but not null |
| `companyId`, `departmentId`, `designationId` | no | The ids from step 0 |
| `supervisorUserId` | no | Without it nobody can approve their leave. Cycles are rejected |
| `role` | no | Defaults to `EMPLOYEE` |
| `overtimeEligible` | no | Overtime is *measured* for everyone but only **paid** if `true` |
| `joiningDate`, `dateOfBirth` | no | Feed the dashboard's anniversary and birthday cards |

### Manual salary structure override and regenerate

`basicDA`/`hra`/`conveyanceAllowance`/`educationAllowance` are derived at
creation from the fields above and the active `SalaryRule` - but a real
payslip sometimes needs to differ from the formula. Override them by hand:

```
PUT http://localhost:8080/api/employees/5/salary-structure
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{
  "basicDA": 13500,
  "hra": 5400,
  "conveyanceAllowance": 1350,
  "educationAllowance": 1350
}
```

**200 OK** — the employee, with `salaryStructureOverridden: true` and
`grossSalaryWage` recomputed as the new sum (13500 + 5400 + 1350 + 1350 +
1250 medical + 0 other = **22850**). From this point on, neither a plain
`PUT /api/employees/5` nor a `SalaryRule` change will touch those four
fields — only `grossSalaryWage` keeps refreshing if you edit
`medicalAllowance`/`otherAllowance`.

To go back to what `SalaryRule` derives — either to undo the override, or to
pick up a rule change:

```
POST http://localhost:8080/api/employees/5/salary-structure/regenerate
```

**200 OK** — `salaryStructureOverridden: false`, `basicDA`/`hra`/etc. back to
the formula's numbers.

For a whole-company refresh after a `SalaryRule` change (the fix for "I
updated the salary rule and nothing changed"):

```
POST http://localhost:8080/api/employees/salary-structure/regenerate-all
```

**200 OK** — `{"regenerated": 4}`. Every **active, non-overridden** employee
of the caller's company is recomputed; anyone currently overridden is left
alone on purpose — a bulk, rule-driven refresh silently discarding a
deliberate manual value would be a surprise, not a fix. Regenerate an
overridden employee individually if you actually want that.

Neither call touches already-generated payroll. Re-run `/api/payroll/regenerate`
(Step 11) for any period you want to reflect the corrected structure.

---

## Step 3 — Create a holiday

```
POST http://localhost:8080/api/holidays
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{
  "companyId": 1,
  "holidayName": "Ganesh Chaturthi",
  "holidayDate": "2026-09-14",
  "optionalHoliday": false
}
```

**201 Created** — a mandatory holiday is removed from working days, so it can
never become loss of pay. `optionalHoliday: true` leaves it a working day.

---

## Step 4 — Roster the month

**Skip this and everything breaks.** No roster means no expected working days,
so the employee reads as absent all month and the entire salary becomes LOP.

```
POST http://localhost:8080/api/shift-schedules/bulk
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{
  "userIds": ["EMP005"],
  "fromDate": "2026-09-01",
  "toDate": "2026-09-30",
  "shiftCode": "MORNING",
  "weekOffDays": ["SUNDAY"],
  "skipHolidays": true,
  "overwriteExisting": true,
  "assignedBy": "SUP001"
}
```

**200 OK** — an array of **29** rows:

```json
[
  { "id": 1, "userId": "EMP005", "shiftDate": "2026-09-01", "shiftCode": "MORNING",
    "shiftName": "Morning", "startTime": "06:00:00", "endTime": "15:00:00",
    "weekOff": false, "assignedBy": "SUP001" },
  { "id": 2, "userId": "EMP005", "shiftDate": "2026-09-02", "...": "..." }
]
```

The arithmetic that matters:

```
30 days in September
 -1 holiday (14th, not scheduled at all)
= 29 rows returned
 -4 Sundays (scheduled but weekOff: true)
= 25 WORKING DAYS
```

| Field | Effect |
|---|---|
| `weekOffDays` | Those weekdays are scheduled but flagged `weekOff` |
| `skipHolidays` | Mandatory holidays are not scheduled at all |
| `overwriteExisting` | `false` returns `409` on any day already scheduled |
| `assignedBy` | If a supervisor, they may only schedule **their own team** |

Check it visually:

```
GET  http://localhost:8080/api/shift-schedules/planner?month=2026-09&supervisorUserId=SUP001
```

```
01:MORNING 02:MORNING 03:MORNING 04:MORNING 05:MORNING 06:WO      07:MORNING
08:MORNING 09:MORNING 10:MORNING 11:MORNING 12:MORNING 13:WO      15:MORNING
16:MORNING 17:MORNING 18:MORNING 19:MORNING 20:WO      21:MORNING 22:MORNING
23:MORNING 24:MORNING 25:MORNING 26:MORNING 27:WO      28:MORNING 29:MORNING
30:MORNING
```

`WO` = weekly off. The 14th is missing entirely — that is the holiday.

---

## Step 5 — Load punches (DB, not API)

**There is no endpoint for attendance punches.** The eSSL device's middleware
writes rows straight into `device_logs`; this system only reads them. That is
deliberate — attendance cannot be edited by hand.

For testing, run the supplied SQL:

```bash
mysql -uroot -proot alsama < docs/testing/device_logs_EMP005_2026-09.sql
```

### The table

| Column | Type | Notes |
|---|---|---|
| `device_log_id` | bigint | Primary key. **Not auto-generated** — supply it yourself |
| `device_id` | bigint | Which reader |
| `user_id` | varchar(50) | **Must equal the employee's `userId`** |
| `log_date` | datetime | The punch instant |

A minimal manual insert:

```bash
mysql -uroot -proot alsama -e "INSERT INTO device_logs (device_log_id, device_id, user_id, log_date) VALUES (950001,1,'EMP005','2026-09-01 06:00:00'),(950002,1,'EMP005','2026-09-01 15:00:00');"
```

### What the supplied file contains

44 rows = 22 worked days, in at 06:00 and out at 15:00, except:

| Date | What | Why it is there |
|---|---|---|
| **17 Sep** | out at **18:30** | Overtime day → 11.5 worked hours, 3.5 overtime |
| **22–23 Sep** | no punches | She will apply for leave → becomes paid leave |
| **29 Sep** | no punches | Absent, no leave → becomes 1 LOP day |
| Sundays | no punches | Weekly off |
| 14 Sep | no punches | Holiday, not rostered |

### How punches are interpreted

- Window = **60 minutes before** the shift start, to **`overtimeWindowMinutes`
  after** the scheduled end (default 240 = 4 hours). Asymmetric on purpose: a
  late exit is overtime, not a missing punch.
- **First punch = in, last punch = out.**
- 4+ punches → the middle gaps are the real break. Otherwise the shift's
  `breakMinutes` applies.
- Worked = span − break. **75%** of the shift = full day, **40%** = half day.
- **One lone punch** = `INVALID_PUNCH`, a device error — never silently an
  absence.

---

## Step 6 — Attendance BEFORE leave

```
GET  http://localhost:8080/api/attendance/EMP005/monthly?month=2026-09
Headers: Authorization: Bearer <token>
```

**200 OK**

```json
{
  "userId": "EMP005",
  "employeeName": "Priya Kulkarni",
  "month": "2026-09",
  "workingDays": 25,
  "presentDays": 22.0,
  "absentDays": 3.0,
  "halfDays": 0,
  "leaveDays": 0.0,
  "holidayDays": 0,
  "weekOffDays": 4,
  "lateCount": 0,
  "earlyExitCount": 0,
  "invalidPunches": 0,
  "totalHours": 179.5,
  "overtimeHours": 3.5,
  "lopDays": 3.0,
  "days": [ "...one entry per rostered day..." ]
}
```

**All three absences are unpaid right now** because no leave has been approved.

Individual days from the `days` array:

```json
{ "attendanceDate": "2026-09-01", "shiftCode": "MORNING",
  "firstIn": "2026-09-01T06:00:00", "lastOut": "2026-09-01T15:00:00",
  "workingHours": 8.0, "breakHours": 1.0, "overtimeHours": 0.0,
  "lateMinutes": 0, "earlyExitMinutes": 0, "invalidPunch": false, "status": "PRESENT" }

{ "attendanceDate": "2026-09-06", "firstIn": null, "lastOut": null,
  "workingHours": 0.0, "status": "WEEKLY_OFF" }

{ "attendanceDate": "2026-09-17", "firstIn": "2026-09-17T06:00:00",
  "lastOut": "2026-09-17T18:30:00", "workingHours": 11.5,
  "overtimeHours": 3.5, "status": "PRESENT" }

{ "attendanceDate": "2026-09-29", "firstIn": null, "lastOut": null,
  "workingHours": 0.0, "status": "ABSENT" }
```

The 17th: 12.5 hours between punches − 1 hour break = **11.5 worked**, of which
**3.5 is beyond the 8-hour shift**.

### What to check, in order

| Field | If not zero |
|---|---|
| `invalidPunches` | **Fix first.** Someone badged once. A device error must not become an absence |
| `absentDays` | Genuinely absent, **or** leave not yet approved |
| `lateCount` / `earlyExitCount` | Arrival past grace / departure before shift end |

Possible `status` values: `PRESENT`, `HALF_DAY`, `ABSENT`, `ON_LEAVE`,
`WEEKLY_OFF`, `HOLIDAY`, `INVALID_PUNCH`.

---

## Step 7 — Apply for leave

Check the balance first — quotas seed themselves on first read:

```
GET  http://localhost:8080/api/leave-balances/EMP005?year=2026
```

```json
[
  { "userId": "EMP005", "leaveYear": 2026, "leaveType": "CASUAL_LEAVE",
    "quota": 12.0, "used": 0.0, "available": 12.0 },
  { "leaveType": "SICK_LEAVE",        "quota": 8.0, "used": 0.0, "available": 8.0 },
  { "leaveType": "LEAVE_WITHOUT_PAY", "quota": 0.0, "used": 0.0, "available": 0.0 }
]
```

```
POST http://localhost:8080/api/leaves
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{
  "userId": "EMP005",
  "leaveType": "CASUAL_LEAVE",
  "fromDate": "2026-09-22",
  "toDate": "2026-09-23",
  "duration": "FULL_DAY",
  "reason": "Family function"
}
```

**201 Created**

```json
{
  "id": 1,
  "userId": "EMP005",
  "employeeName": "Priya Kulkarni",
  "leaveType": "CASUAL_LEAVE",
  "fromDate": "2026-09-22",
  "toDate": "2026-09-23",
  "duration": "FULL_DAY",
  "totalDays": 2.0,
  "reason": "Family function",
  "status": "PENDING",
  "supervisorId": "SUP001",
  "approverId": null,
  "approvalComments": null,
  "appliedAt": "2026-08-03T12:27:27.775818Z",
  "decidedAt": null
}
```

**Keep the `id`** — you need it for the next two calls. (The Postman collection
stores it in a `leaveId` variable automatically.)

`leaveType`: `CASUAL_LEAVE` | `SICK_LEAVE` | `LEAVE_WITHOUT_PAY`
`duration`: `FULL_DAY` | `FIRST_HALF` | `SECOND_HALF` — halves need
`fromDate == toDate`.

Rejected at this point rather than at approval: overlapping leave, a range
spanning two calendar years, a half day across dates, insufficient balance.

---

## Step 8 — Supervisor endorses

```
GET  http://localhost:8080/api/leaves/pending/SUP001
```

```json
[{ "id": 1, "userId": "EMP005", "employeeName": "Priya Kulkarni",
   "fromDate": "2026-09-22", "toDate": "2026-09-23", "status": "PENDING" }]
```

```
POST http://localhost:8080/api/leaves/1/supervisor-approve
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{ "approverId": "SUP001", "comments": "Cover arranged" }
```

**200 OK** — `"status": "SUPERVISOR_APPROVED"`.

**Balance has not moved.** This is an endorsement, not the decision. Leave it
here and the employee still loses the pay.

---

## Step 9 — HR approves

```
POST http://localhost:8080/api/leaves/1/approve
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{ "approverId": "HR001", "comments": "Approved" }
```

**200 OK** — `"status": "APPROVED"`, `"approverId": "HR001"`, `decidedAt` set.

**This is the step that consumes balance.** Only `HR` or `ADMIN` may call it.

```
GET  http://localhost:8080/api/leave-balances/EMP005?year=2026
```

```json
[{ "leaveType": "CASUAL_LEAVE", "quota": 12.0, "used": 2.0, "available": 10.0 }]
```

| Action | Balance |
|---|---|
| `POST /api/leaves/{id}/approve` | **consumed** |
| `POST /api/leaves/{id}/reject` | untouched |
| `POST /api/leaves/{id}/cancel` | **restored**, if it had been approved |

All three take the same body: `{ "approverId": "...", "comments": "..." }`.

---

## Step 10 — Attendance AFTER approval

The same call as step 6. This is the comparison worth seeing:

```
GET  http://localhost:8080/api/attendance/EMP005/monthly?month=2026-09
```

```json
{
  "workingDays": 25,
  "presentDays": 22.0,
  "absentDays": 1.0,
  "leaveDays": 2.0,
  "weekOffDays": 4,
  "totalHours": 179.5,
  "overtimeHours": 3.5,
  "lopDays": 1.0
}
```

| | Before | After |
|---|---|---|
| `leaveDays` | 0.0 | **2.0** |
| `absentDays` | 3.0 | **1.0** |
| `lopDays` | 3.0 | **1.0** |

```
LOP = working days − present days − approved paid leave
    = 25 − 22 − 2
    = 1        (only the 29th, where she never applied)
```

**Settle every pending leave before payroll.** Anything left `PENDING` or
`SUPERVISOR_APPROVED` costs the employee a day's pay.

---

## Step 11 — Generate payroll

```
POST http://localhost:8080/api/payroll/generate
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{
  "employeeId": "EMP005",
  "month": 9,
  "year": 2026,
  "advanceDeduction": 2000,
  "loanDeduction": 0,
  "tds": 0,
  "canteen": 450,
  "bonus": 1000,
  "incentive": 0,
  "generatedBy": "HR001"
}
```

Note `month` and `year` are **separate numbers** here, not `2026-09`.

**201 Created**

```json
{
  "id": 1,
  "employeeId": "EMP005",
  "month": 9,
  "year": 2026,
  "revision": 1,
  "status": "GENERATED",

  "employeeName": "Priya Kulkarni",
  "employeeCode": "EMP-005",
  "companyName": "Accusharp Industries",
  "departmentName": "Production",
  "designationName": "Senior Operator",
  "employmentStatus": "PERMANENT",
  "grossSalary": 26000.0,
  "grossSalaryWage": 22050.0,
  "pfBasic": 9000.0,

  "daysInMonth": 30,
  "workingDays": 25,
  "presentDays": 22.0,
  "paidLeaveDays": 2.0,
  "lopDays": 1.0,
  "payableDays": 24.0,
  "totalHours": 179.5,
  "overtimeHours": 3.5,
  "perDay": 1040.0,
  "perHour": 130.0,

  "earnBasicDA": 12480.0,
  "earnHra": 4992.0,
  "earnConveyance": 1248.0,
  "earnEducation": 1248.0,
  "earnMedical": 1200.0,
  "earnOther": 0.0,
  "bonus": 1000.0,
  "incentive": 0.0,
  "otAllowance": 455.0,
  "earnGrossSalary": 21168.0,
  "totalEarnings": 22623.0,

  "pf": 1080.0,
  "earnPf": 8640.0,
  "pfDeduction": 1036.8,
  "esic": 0.0,
  "professionalTax": 200.0,
  "tds": 0.0,
  "advanceDeduction": 2000.0,
  "loanDeduction": 0.0,
  "canteen": 450.0,
  "lopDeduction": 882.0,
  "totalDeduction": 3686.8,

  "netSalary": 18936.2,

  "ruleBasicDaPercent": 50.0,
  "rulePfPercent": 12.0,
  "ruleEsicPercent": 0.75,
  "generatedAt": "2026-08-03T12:27:48.034809Z",
  "generatedBy": "HR001"
}
```

### Where every number came from

**`payableDays` = 24** — 25 working days − 1 LOP day.

**Earnings prorated 24/25:**

```
earnBasicDA = 13000 × 24/25 = 12480
earnHra     =  5200 × 24/25 =  4992
```

**`otAllowance` = 455** — `perDay` = 26000 ÷ 25 = 1040; `perHour` = 1040 ÷ 8 =
130; 3.5 h × 130 × 1.0 multiplier = 455. Paid only because
`overtimeEligible: true`.

**`pfDeduction` = 1036.80** — PF basic 9000 prorated to `earnPf` 8640 (24/25),
then 12%. PF is charged on the **prorated** basic. The `pf` field (1080 = full
month) is informational.

**`esic` = 0** — earned gross 22623 exceeds the 21000 ceiling, so she is outside
the scheme.

**`professionalTax` = 200** — gross 26000 is above the 10001 slab threshold.

**`lopDeduction` = 882 is NOT subtracted.** Earnings were already reduced from
25 days to 24. Subtracting it again would charge her twice for the same day —
it appears purely so the employee can see what the missing day cost.

```
netSalary = totalEarnings − totalDeduction
          = 22623.00 − 3686.80
          = 18936.20
```

### Whole company at once

```
POST http://localhost:8080/api/payroll/generate-all?month=9&year=2026
Headers: Authorization: Bearer <token>
Body:    none
```

(`generatedBy` is not a query parameter here - like every other actor field in
this document, it's always the caller from the bearer token.)

Skips anyone already generated, so it is safe to re-run. Uses **zero for all
manual deductions** — generate anyone with an advance or canteen amount
individually.

---

## Step 12 — Salary slip

```
GET  http://localhost:8080/api/salary-slips/EMP005?month=9&year=2026
```

**200 OK**

```json
{
  "companyName": "Accusharp Industries",
  "employeeId": "EMP005",
  "employeeCode": "EMP-005",
  "employeeName": "Priya Kulkarni",
  "departmentName": "Production",
  "designationName": "Senior Operator",
  "period": "September 2026",
  "attendance": {
    "daysInMonth": 30, "workingDays": 25, "presentDays": 22.0,
    "paidLeaveDays": 2.0, "lopDays": 1.0, "payableDays": 24.0,
    "totalHours": 179.5, "overtimeHours": 3.5
  },
  "earnings": [
    { "label": "Basic + DA",            "amount": 12480.0 },
    { "label": "HRA",                   "amount": 4992.0 },
    { "label": "Conveyance Allowance",  "amount": 1248.0 },
    { "label": "Education Allowance",   "amount": 1248.0 },
    { "label": "Medical Allowance",     "amount": 1200.0 },
    { "label": "Overtime Allowance",    "amount": 455.0 },
    { "label": "Bonus",                 "amount": 1000.0 }
  ],
  "deductions": [
    { "label": "Provident Fund",   "amount": 1036.8 },
    { "label": "Professional Tax", "amount": 200.0 },
    { "label": "Advance",          "amount": 2000.0 },
    { "label": "Canteen",          "amount": 450.0 }
  ],
  "totalEarnings": 22623.0,
  "totalDeductions": 3686.8,
  "netSalary": 18936.2,
  "netSalaryInWords": "Eighteen Thousand Nine Hundred and Thirty Six Rupees and Twenty Paise Only",
  "revision": 1,
  "generatedAt": "2026-08-03T12:27:48.034809Z"
}
```

Zero-value lines are omitted from `earnings` and `deductions`.

### Printable slip

```
GET  http://localhost:8080/api/salary-slips/EMP005/print?month=9&year=2026
```

Returns `text/html`. **Open this one in a browser**, not Postman — then print or
save as PDF.

### CSV export

```
GET  http://localhost:8080/api/salary-slips/export?month=9&year=2026
```

```csv
employeeId,employeeCode,employeeName,department,designation,workingDays,presentDays,paidLeaveDays,lopDays,payableDays,totalEarnings,totalDeductions,netSalary
EMP005,EMP-005,Priya Kulkarni,Production,Senior Operator,25,22.0,2.0,1.0,24.0,22623.00,3686.80,18936.20
```

---

## Step 13 — Reports

**Watch the month format** — attendance reports take `month=2026-09`, payroll
reports take `month=9&year=2026`.

```
GET  http://localhost:8080/api/reports/attendance/overtime?month=2026-09
```

```json
[{ "userId": "EMP005", "employeeName": "Priya Kulkarni",
   "date": "2026-09-30", "detail": "3.50 overtime hour(s)" }]
```

```
GET  http://localhost:8080/api/reports/attendance/lop?month=2026-09
GET  http://localhost:8080/api/reports/statutory/pf?month=9&year=2026
```

```json
[{ "userId": "EMP005", "detail": "1.0 LOP day(s)" }]

[{ "userId": "EMP005", "employeeName": "Priya Kulkarni",
   "base": 8640.0, "amount": 1036.8 }]
```

Reports only aggregate what attendance and payroll recorded — they never
recalculate, so a report can never disagree with a payslip.

| Report | URL |
|---|---|
| Monthly attendance | `/api/reports/attendance/monthly?month=2026-09` |
| Late coming | `/api/reports/attendance/late-coming?month=2026-09` |
| Absent | `/api/reports/attendance/absent?month=2026-09` |
| Overtime | `/api/reports/attendance/overtime?month=2026-09` |
| LOP | `/api/reports/attendance/lop?month=2026-09` |
| Leave balances | `/api/reports/leave-balances?year=2026` |
| Payroll | `/api/reports/payroll?month=9&year=2026` |
| By department | `/api/reports/payroll/by-department?month=9&year=2026` |
| By company | `/api/reports/payroll/by-company?month=9&year=2026` |
| PF | `/api/reports/statutory/pf?month=9&year=2026` |
| Professional tax | `/api/reports/statutory/professional-tax?month=9&year=2026` |
| ESIC | `/api/reports/statutory/esic?month=9&year=2026` |
| Dashboard | `/api/dashboard?asOf=2026-09-15` |

---

## Step 14 — Correct a mistake

The canteen amount should have been ₹300. Running generate again is refused:

```
POST http://localhost:8080/api/payroll/generate
{ "employeeId": "EMP005", "month": 9, "year": 2026 }
```

**409 Conflict**

```json
{
  "timestamp": "2026-08-03T12:27:48.202358Z",
  "status": 409,
  "error": "Conflict",
  "message": "Payroll already generated for EMP005 for 9/2026 - use the regenerate endpoint",
  "path": "/api/payroll/generate"
}
```

Use regenerate:

```
POST http://localhost:8080/api/payroll/regenerate
Headers: Authorization: Bearer <token>, Content-Type: application/json
```

```json
{
  "employeeId": "EMP005",
  "month": 9,
  "year": 2026,
  "advanceDeduction": 2000,
  "canteen": 300,
  "bonus": 1000,
  "generatedBy": "HR001"
}
```

**200 OK** — `"revision": 2`, `"canteen": 300.0`, `"netSalary": 19086.2`.

```
GET  http://localhost:8080/api/payroll/employee/EMP005/revisions?month=9&year=2026
```

```json
[
  { "id": 2, "revision": 2, "status": "GENERATED",  "canteen": 300.0, "netSalary": 19086.2 },
  { "id": 1, "revision": 1, "status": "SUPERSEDED", "canteen": 450.0, "netSalary": 18936.2 }
]
```

**Revision 1 was not overwritten.** If someone asks in March why September's
slip said ₹18,936.20, the record is still there.

The same protection runs the other way: each payroll row snapshots the salary
structure and rule percentages at generation time, so a raise in October never
changes September's payslip.

---

## Error responses

Every failure returns the same shape.

**400 — validation.** All field failures in one message:

```json
{
  "timestamp": "2026-08-03T12:27:48.281697Z",
  "status": 400,
  "error": "Bad Request",
  "message": "employeeCode: must not be blank; otherAllowance: must not be null; userId: must not be blank; medicalAllowance: must not be null; grossSalary: must be greater than 0; status: must not be null; employeeName: must not be blank; pfBasic: must not be null",
  "path": "/api/employees"
}
```

**400 — supervisor acting outside their team:**

```json
{ "status": 400, "error": "Bad Request",
  "message": "Supervisor SUP001 does not manage employee HR001",
  "path": "/api/shift-schedules" }
```

**400 — business rules**, e.g. `"An open or approved leave already covers part
of this range"`, `"Final leave approval requires the HR or ADMIN role"`,
`"A half day leave must start and end on the same date"`.

**404 — not found:**

```json
{ "status": 404, "error": "Not Found",
  "message": "Employee not found: userId NOPE",
  "path": "/api/employees/by-user-id/NOPE" }
```

**409 — conflict:** duplicate code, or a payroll period already generated.

| Status | Meaning |
|---|---|
| `400` | Validation or a business rule — the message says which |
| `404` | Referenced record does not exist |
| `409` | Duplicate code, or payroll period already generated |
| `500` | Genuine bug — check the application log |

---

## Useful SQL

Check punches actually landed:

```bash
mysql -uroot -proot alsama -e "SELECT user_id, DATE(log_date) d, COUNT(*) punches, MIN(log_date) first_in, MAX(log_date) last_out FROM device_logs WHERE user_id='EMP005' GROUP BY user_id, DATE(log_date) ORDER BY d;"
```

Find device ids that do not match any employee — the classic cause of "this
person shows zero attendance":

```bash
mysql -uroot -proot alsama -e "SELECT DISTINCT d.user_id FROM device_logs d LEFT JOIN employee e ON e.user_id = d.user_id WHERE e.id IS NULL;"
```

Find days with an odd punch count (the ones that become `INVALID_PUNCH`):

```bash
mysql -uroot -proot alsama -e "SELECT user_id, DATE(log_date) d, COUNT(*) c FROM device_logs GROUP BY user_id, DATE(log_date) HAVING c % 2 <> 0 ORDER BY d;"
```

Check the roster:

```bash
mysql -uroot -proot alsama -e "SELECT user_id, shift_date, week_off FROM emp_attendance_shift WHERE user_id='EMP005' ORDER BY shift_date;"
```

Reset just this employee's test data and start again:

```bash
mysql -uroot -proot alsama -e "DELETE FROM payroll WHERE employee_id='EMP005'; DELETE FROM leave_request WHERE user_id='EMP005'; DELETE FROM leave_balance WHERE user_id='EMP005'; DELETE FROM emp_monthly_attendance_summary WHERE user_id='EMP005'; DELETE FROM emp_attendance_shift WHERE user_id='EMP005'; DELETE FROM device_logs WHERE user_id='EMP005';"
```

Wipe everything and re-seed from scratch:

```bash
mysql -uroot -proot -e "DROP DATABASE alsama; CREATE DATABASE alsama;"
```

### Table names

| Table | Holds |
|---|---|
| `company`, `department`, `designation` | Masters |
| `employee` | Employee master, `user_id` is the business key |
| `shift` | Shift definitions |
| `emp_attendance_shift` | The roster: one row per employee per day |
| `device_logs` | **Raw punches — written by the device, read-only here** |
| `holiday` | Company calendar |
| `leave_request`, `leave_balance` | Leave |
| `emp_monthly_attendance_summary` | Cached monthly rollup, recomputed on read |
| `salary_rule` | The single config row (`id = 1`) |
| `payroll` | Immutable payroll snapshots, one per revision |

---

## Full endpoint list

Masters follow standard REST — `POST` create, `PUT /{id}` update, `GET /{id}`,
`GET` list, `DELETE /{id}`.

| Module | Base path |
|---|---|
| Companies | `/api/companies` |
| Departments | `/api/departments` |
| Designations | `/api/designations` |
| Employees | `/api/employees` (+ `POST /bulk-import`) |
| Shift master | `/api/shifts` |
| Shift scheduling | `/api/shift-schedules` (+ `POST /bulk/varied`, `POST /bulk/csv`) |
| Attendance | `/api/attendance` |
| Holidays | `/api/holidays` |
| Leave | `/api/leaves` |
| Leave balances | `/api/leave-balances` |
| Salary rules | `/api/salary-rules` |
| Payroll | `/api/payroll` (+ `POST /bulk-generate`, `GET /debug`) |
| Salary slips | `/api/salary-slips` |
| Reports | `/api/reports` |
| Dashboard | `/api/dashboard` |

### Beyond the walkthrough

```
POST /api/shift-schedules/auto-rotate
{ "userIds": ["EMP001","EMP002","EMP005"],
  "shiftCycle": ["MORNING","EVENING","NIGHT"],
  "fromDate": "2026-10-01", "toDate": "2026-10-31",
  "rotationDays": 7, "weekOffDays": ["SUNDAY"],
  "skipHolidays": true, "assignedBy": "SUP001" }

POST /api/shift-schedules/copy-month
{ "userIds": ["EMP005"], "sourceMonth": "2026-09",
  "targetMonth": "2026-10", "overwriteExisting": false, "assignedBy": "SUP001" }

POST /api/shift-schedules/swap
{ "firstUserId": "EMP001", "secondUserId": "EMP005",
  "shiftDate": "2026-10-12", "assignedBy": "SUP001" }

POST /api/shift-schedules/holiday-override?month=2026-10

PATCH /api/employees/EMP005/supervisor?supervisorUserId=SUP001
GET   /api/employees/SUP001/team
PUT   /api/leave-balances/EMP005?year=2026&leaveType=CASUAL_LEAVE&quota=15
POST  /api/attendance/summaries/refresh?month=2026-09

PUT  /api/employees/5/salary-structure
{ "basicDA": 13500, "hra": 5400, "conveyanceAllowance": 1350, "educationAllowance": 1350 }
POST /api/employees/5/salary-structure/regenerate
POST /api/employees/salary-structure/regenerate-all
```

See [Step 2](#step-2--create-the-employee)'s "Manual salary structure override
and regenerate" for what each of the three does and why.

### Bulk & CSV endpoints

All four below share one response shape - `{totalRows, successCount,
failureCount, succeeded: [...], errors: [{rowNumber, identifier, message}]}` -
because every row is attempted independently: one bad row (a duplicate code,
an unknown employee, a period already generated) fails only that row and
shows up in `errors`, it never aborts the rest of the batch. The three
`multipart/form-data` ones need Postman's **Body → form-data**, key `file`,
type **File** — not raw JSON.

```
POST /api/employees/bulk-import                            (multipart, key "file")
Header row: userId,employeeCode,employeeName,companyId,departmentId,designationId,
            supervisorUserId,joiningDate,dateOfBirth,status,role,email,phone,
            grossSalary,pfBasic,medicalAllowance,otherAllowance,overtimeEligible
Required: userId, employeeCode, employeeName, status, grossSalary, pfBasic,
          medicalAllowance, otherAllowance. Same admin-escalation guard and
          one-time temporaryPassword-per-row as Step 2.

POST /api/shift-schedules/bulk/varied                       (JSON — each entry its own shift, unlike /bulk)
{ "assignments": [
    { "userId": "EMP001", "shiftDate": "2026-10-01", "shiftCode": "MORNING", "weekOff": false },
    { "userId": "EMP003", "shiftDate": "2026-10-01", "shiftCode": "NIGHT",   "weekOff": false }
] }

POST /api/shift-schedules/bulk/csv                           (multipart, key "file")
Header row: userId,shiftDate,shiftCode,weekOff

POST /api/payroll/bulk-generate?month=9&year=2026&regenerate=false   (multipart, key "file")
Header row: employeeId,bonus,incentive,tds,advanceDeduction,loanDeduction,canteen
Only employeeId is required; every amount defaults to 0. regenerate=true
recomputes an already-generated row as a new revision instead of erroring it.

GET /api/payroll/debug?month=9&year=2026
Same rows /api/payroll?month=&year= returns, plus liveGrossSalary/livePfBasic/
liveRuleBasicDaPercent/liveRulePfPercent/liveRuleEsicPercent read fresh at
request time, and masterDataDrifted/ruleDrifted booleans - true means the
employee's salary or the company's SalaryRule changed after this payroll was
generated. Use this instead of /api/payroll when a net salary looks wrong and
you need to see every input the calculation used, not just the outputs.
```

---

## Before you rely on this

Authentication and authorization both exist now (JWT bearer tokens,
permission-based `@PreAuthorize` on every endpoint, multi-tenant company
isolation, self-service scoping) - see [SECURITY.md](SECURITY.md) for the
full picture, including what's still genuinely open: dynamic/admin-editable
roles, a real forgot-password email flow, audit log retention tooling, and
full audit coverage of every sensitive action.

Also: set `hrms.seed.enabled=false` and remove the demo employees before loading
real data.
