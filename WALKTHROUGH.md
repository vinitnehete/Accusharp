# Step-by-Step Walkthrough

One complete cycle for one real employee: hire her, roster her, take her
punches, process her leave, run her salary, print her slip and correct a
mistake.

**Every number below was produced by actually running these steps** against the
application, not written from the specification. You can replay them exactly.

Our employee: **Priya Kulkarni**, machine operator, gross ₹26,000/month,
reporting to supervisor `SUP001`. Month: **September 2026**.

For the reference guide see [README.md](README.md); for exact URLs, headers,
bodies and responses to paste into Postman see [TESTING.md](TESTING.md); for how
it works internally see [ARCHITECTURE.md](ARCHITECTURE.md); for the full
authentication/authorization model see [SECURITY.md](SECURITY.md).

---

## Before you start

Start the app (H2 is fine for practice - nothing touches your real database):

```bash
cd /Users/vinitnehete/Downloads/Accusharp && ./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

Everything below is a `curl` command you can paste into a terminal. If you
prefer Postman or a browser REST client, the method, URL and JSON body are all
visible in each command.

---

## Step 0 — Log in

**Every `/api/**` endpoint (except `/api/auth/**` itself) requires a bearer
token.** Log in as the seeded HR user - HR holds essentially every permission
this walkthrough needs (everything short of platform-level company creation),
so one token carries you through the whole thing:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"HR001","password":"Accusharp@123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
```

(No `jq`? The Python one-liner above works anywhere Python 3 is installed. Or
just read `accessToken` out of the JSON by eye and paste it into `$TOKEN`
yourself.) Every command below assumes `$TOKEN` is set and adds
`-H "Authorization: Bearer $TOKEN"`. The token expires in 15 minutes
(`POST /api/auth/refresh` with the `refreshToken` from the same login
response gets you a new one without logging in again).

**Two things that changed shape once auth landed, worth knowing before you
start:**

- **`assignedBy`, `approverId`, `updatedBy`, `generatedBy` fields you see in
  request bodies below are decorative.** The server always overwrites them
  with whoever's bearer token made the call - never trust a client-supplied
  actor identity for something audit-relevant. Sending them is harmless (and
  the request DTOs still declare them for backward compatibility), but if you
  change `$TOKEN` to a different user mid-walkthrough, *that* is what
  determines who did what, not the JSON body.
- **A plain `EMPLOYEE` or `SUPERVISOR` token sees less than HR's.** This
  walkthrough uses `HR001` throughout precisely to avoid that complexity -
  HR/ADMIN are unrestricted within their own company. If you log in as
  `EMP001` instead, `GET /api/employees` returns only `EMP001`'s own record,
  `GET /api/attendance/EMP002/monthly` 404s, and so on - see SECURITY.md's
  "self-service scoping" section. Worth trying once you've done the
  walkthrough as HR, to see the difference.

**Starting from zero companies instead of the seeded demo data?** Use
`POST /api/companies/onboard` (platform-owner only - see SECURITY.md) to
create a brand-new company and its first `ADMIN` in one step, then log in as
that admin instead of `HR001` for everything below.

---

## Step 1 — See what already exists

A fresh database is pre-loaded with a company, two departments, two
designations, five categories, four shifts and four demo employees.

```bash
curl http://localhost:8080/api/companies -H "Authorization: Bearer $TOKEN"
```

```bash
curl http://localhost:8080/api/departments -H "Authorization: Bearer $TOKEN"
```

```bash
curl http://localhost:8080/api/designations -H "Authorization: Bearer $TOKEN"
```

```bash
curl http://localhost:8080/api/categories -H "Authorization: Bearer $TOKEN"
```

What came back:

```
 company  id=1 ACC    Accusharp Industries
 dept     id=1 PROD   Production
 dept     id=2 ADMIN  Administration
 desig    id=1 OPR    Machine Operator
 desig    id=2 MGR    Manager
 category id=1 WORKER     Worker
 category id=2 STAFF      Staff
 category id=3 SUPERVISOR Supervisor
 category id=4 MANAGER    Manager
 category id=5 DIRECTOR   Director
```

`category` is the employee grade - these five are shared defaults every
company starts with; add more via `POST /api/categories` the same way as a
department or designation. Unlike department/designation, it's optional on
an employee.

**Write down the `id` values.** You need them when creating an employee.

Existing people, so you know who can approve things:

```bash
curl http://localhost:8080/api/employees -H "Authorization: Bearer $TOKEN"
```

| userId | Name | Role | Can do |
|---|---|---|---|
| `HR001` | Meera Joshi | HR | Final leave approval, schedule anyone |
| `SUP001` | Rakesh Patil | SUPERVISOR | Endorse leave and schedule **for their own team only** |
| `EMP001`, `EMP002` | Sunil, Anita | EMPLOYEE | Apply for leave |

---

## Step 2 — Add a designation

Priya is a senior operator and that grade does not exist yet.

```bash
curl -X POST http://localhost:8080/api/designations -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"designationCode":"SR-OPR","designationName":"Senior Operator","description":"Senior grade"}'
```

```json
{ "id": 3, "designationCode": "SR-OPR", "designationName": "Senior Operator" }
```

Note **`id: 3`** - that is what we pass as `designationId` next.

(Since Phase 6, `Department`/`Designation`/`Shift` codes only have to be
unique *within your own company* - a second company can create its own
`SR-OPR` too, without colliding with this one.)

---

## Step 3 — Add the employee

```bash
curl -X POST http://localhost:8080/api/employees -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"userId":"EMP005","employeeCode":"EMP-005","employeeName":"Priya Kulkarni","companyId":1,"departmentId":1,"designationId":3,"categoryId":2,"supervisorUserId":"SUP001","joiningDate":"2024-02-12","dateOfBirth":"1996-09-20","gender":"FEMALE","status":"PERMANENT","role":"EMPLOYEE","email":"priya@accusharp.example","phone":"9822001122","grossSalary":26000,"pfBasic":9000,"medicalAllowance":1250,"otherAllowance":0,"overtimeEligible":true}'
```

`categoryId` (Staff, from the seeded list above) and `gender` are both
optional, same as `uanNo`/`esicIpNo`/`bankAccountNo`/`bankIfscNo` if we had
them on hand - none of the six affect any calculation, they're informational.

Result:

```json
{
  "employee": {
    "id": 5, "userId": "EMP005", "employeeName": "Priya Kulkarni",
    "grossSalary": 26000.0, "pfBasic": 9000.0, "medicalAllowance": 1250.0, "otherAllowance": 0.0,
    "basicDA": 13000.0, "hra": 5200.0, "conveyanceAllowance": 1300.0, "educationAllowance": 1300.0,
    "grossSalaryWage": 22050.0
  },
  "temporaryPassword": "Tp7-xxxxxxxxxxxxxxxxxxxxxx"
}
```

> **Onboarding a whole batch instead of just Priya?** `POST
> /api/employees/bulk-import` takes a CSV upload - one row per employee, same
> fields as above, same temporary-password-per-row contract. A bad row (a
> duplicate code, a missing amount) fails only that row; the rest of the file
> still gets created. For a real batch, add `?format=csv` to get back a
> downloadable credentials sheet instead of hunting passwords out of a JSON
> array one by one - there's no email/SMS infrastructure in this app to send
> them automatically. See
> [README.md §3.4.2](README.md#342-bulk-import-from-csv).

**Capture `temporaryPassword` now.** It's returned exactly once, right here,
and never logged anywhere - it's how Priya logs in for the first time
(`POST /api/auth/login` with `username: "EMP005"`, then she should change it
via `POST /api/auth/change-password`). There is no forgot-password recovery
yet if it's lost before you relay it to her.

**This is the second most important thing to understand about employees** (the
password is the first, now). You entered four money fields. The system worked
out the rest from the salary rule:

```
basicDA    = 26000 x 50%   = 13000
hra        = 13000 x 40%   =  5200
conveyance = 13000 x 10%   =  1300
education  = 13000 x 10%   =  1300
                             ------
           + medical 1250 + other 0
grossSalaryWage            = 22050
```

`grossSalaryWage` can never be sent yourself - it is not on the create/update
DTO at all, always the server-computed sum. `basicDA`/`hra`/
`conveyanceAllowance`/`educationAllowance` normally work the same way, but
*can* be sent - all four together, never some of them - if you already know
Priya's exact breakup (say, migrating her from a previous payroll system) and
don't want it recalculated. Sending only one or two of the four is rejected
outright: a structure that's part typed, part rule-derived isn't really a
fixed structure. Leave all four out, as above, and the rule keeps deriving
them as usual.

Sometimes a real payslip needs to differ from the formula *after* the
employee already exists. For that, there's a dedicated pair of endpoints:

```bash
curl -X PUT http://localhost:8080/api/employees/5/salary-structure -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"basicDA":13500,"hra":5400,"conveyanceAllowance":1350,"educationAllowance":1350}'
```

sets those four fields by hand and marks the employee `salaryStructureOverridden: true` -
from then on neither a plain employee update nor a salary rule change touches
them. To go back to the formula's numbers:

```bash
curl -X POST http://localhost:8080/api/employees/5/salary-structure/regenerate -H "Authorization: Bearer $TOKEN"
```

See the callout after the next table for the whole-company version of this.

> **Giving Priya a raise later is not a plain `PUT /api/employees/5`.** That
> would overwrite `grossSalary` with no record of what it used to be. Use
> `POST /api/employees/5/salary-revision` instead - it updates `grossSalary`,
> re-derives the structure (or, if she's overridden, needs the four
> replacement values in the same request), and logs the change with a reason
> and effective date. `GET /api/employees/5/salary-revisions` returns the
> full history. See [README.md §3.4.3](README.md#343-salary-revision-hike-promotion-correction).

### Three fields that decide everything downstream

| Field | Why it matters |
|---|---|
| **`userId`** | Must equal the biometric device's user id. This is the key that joins device → attendance → leave → payroll. Get it wrong and she shows zero attendance forever. |
| **`status`** | `PERMANENT` = paid on working days minus LOP. `DAY_WISE` = paid only for days attended, over a fixed 26-day base. |
| **`overtimeEligible`** | Overtime hours are measured for everyone, but only **paid** if this is `true`. |

Also note **`supervisorUserId`** - without it, nobody can approve her leave and
no supervisor can schedule her.

> **Changing the salary rule does not retroactively touch anyone who already
> exists.** The breakup is calculated at the moment you save, so check the
> rule first with `GET /api/salary-rules` if you can. If you change it after
> people already exist, fix them with
> `POST /api/employees/salary-structure/regenerate-all` (or
> `/api/employees/{id}/salary-structure/regenerate` for just one) rather than
> re-saving every employee by hand. It skips anyone already
> `salaryStructureOverridden`, so a deliberate manual value is never
> silently discarded by a rule-driven refresh.

> **Granting `"role":"ADMIN"` requires an ADMIN caller.** HR otherwise has full
> employee create/update rights, but cannot mint a new admin account or
> promote itself to one - that request is rejected regardless of what
> `$TOKEN` belongs to, unless it's already an ADMIN's.

---

## Step 4 — Add the month's holidays

```bash
curl -X POST http://localhost:8080/api/holidays -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"companyId":1,"holidayName":"Ganesh Chaturthi","holidayDate":"2026-09-14","optionalHoliday":false}'
```

A mandatory holiday is removed from working days, so it can never become loss of
pay. Set `optionalHoliday: true` for restricted holidays - those stay working
days unless someone actually takes leave. (`companyId` in the body is ignored
for a company-scoped caller like `HR001` - the holiday always lands in your
own company, the same server-derived-not-client-supplied pattern as
`assignedBy`/`approverId` above.)

---

## Step 5 — Roster her for the month

**This is the step people forget, and it silently ruins everything.** With no
roster there are no expected working days, so the employee reads as absent all
month and the whole salary becomes loss of pay.

```bash
curl -X POST http://localhost:8080/api/shift-schedules/bulk -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"userIds":["EMP005"],"fromDate":"2026-09-01","toDate":"2026-09-30","shiftCode":"MORNING","weekOffDays":["SUNDAY"],"skipHolidays":true,"overwriteExisting":true}'
```

Result:

```
 days scheduled : 29
 weekly offs    : 2026-09-06, 2026-09-13, 2026-09-20, 2026-09-27   (the Sundays)
 holiday skipped: 2026-09-14 is not in the roster at all
```

September has 30 days. One is the holiday (not scheduled), four are Sundays
(scheduled but flagged weekly off), leaving **25 actual working days**.

> **Rostering a team where not everyone is on the same shift?** The call
> above puts every listed `userId` on the *same* `shiftCode`. `POST
> /api/shift-schedules/bulk/varied` (or its CSV upload sibling `POST
> /api/shift-schedules/bulk/csv`) takes one `userId`/`shiftDate`/`shiftCode`
> per entry instead, so Priya can be on MORNING while a teammate is on NIGHT
> in the same call. See [README.md §4](README.md#4-every-month-schedule-shifts).

Check it visually:

```bash
curl "http://localhost:8080/api/shift-schedules/planner?month=2026-09&supervisorUserId=SUP001" -H "Authorization: Bearer $TOKEN"
```

```
Priya Kulkarni
  01:MORNING 02:MORNING 03:MORNING 04:MORNING 05:MORNING 06:WO      07:MORNING
  08:MORNING 09:MORNING 10:MORNING 11:MORNING 12:MORNING 13:WO      15:MORNING
  16:MORNING 17:MORNING 18:MORNING 19:MORNING 20:WO      21:MORNING 22:MORNING
  23:MORNING 24:MORNING 25:MORNING 26:MORNING 27:WO      28:MORNING 29:MORNING
  30:MORNING
```

`WO` = weekly off. The 14th is absent from the list entirely - that is the
holiday.

> **A supervisor can only schedule their own team - enforced by *who is
> logged in*, not by an `assignedBy` field in the body.** Log in as `SUP001`
> instead of `HR001` (same login call as Step 0, different username/password)
> and try scheduling `HR001` with that token: `400 Supervisor SUP001 does not
> manage employee HR001`. Scheduling `EMP005` (their own report) with the
> `SUP001` token succeeds identically to the `HR001` example above.
> `supervisorUserId=SUP001` in the planner URL above is similarly just a
> filter for HR/ADMIN callers - a `SUP001` token gets its own team back
> regardless of what (or whether) that query param is set.

Other ways to build a roster: `/auto-rotate` (rotating shift cycles),
`/copy-month` (clone last month), `/swap` (exchange two people on a date).

---

## Step 6 — Punches arrive from the device

**There is no API to enter attendance.** The biometric device's middleware
writes rows straight into the `device_logs` table; this system only reads them.
That is intentional - attendance cannot be edited by hand.

For practice, insert punches yourself:

```bash
mysql -uroot -proot alsama -e "INSERT INTO device_logs (device_log_id, device_id, user_id, log_date) VALUES (950001,1,'EMP005','2026-09-01 06:00:00'),(950002,1,'EMP005','2026-09-01 15:00:00');"
```

`user_id` must match her `userId` exactly.

In this walkthrough Priya punched in at 06:00 and out at 15:00 on every rostered
working day **except**:

- **22nd and 23rd** - she will apply for leave for these
- **29th** - unplanned absence, no leave applied
- **17th** - she stayed late and punched out at **18:30**

---

## Step 7 — Check attendance *before* processing leave

Do this every month. It is where you catch problems while they can still be
fixed.

```bash
curl "http://localhost:8080/api/attendance/EMP005/monthly?month=2026-09" -H "Authorization: Bearer $TOKEN"
```

```
 workingDays 25 | presentDays 22.0 | absentDays 3.0 | leaveDays 0.0
 invalidPunches 0 | lateCount 0 | totalHours 179.5 | overtimeHours 3.5
 lopDays 3.0
```

(Reading someone else's attendance requires HR/ADMIN, or being that person's
own supervisor, or being that person - a plain `EMPLOYEE` token can only ever
read their own. `HR001`'s token bypasses this, which is why it's used
throughout.)

Reading this:

- **25 working days** - 30 days, minus 1 holiday, minus 4 Sundays.
- **22 present** - she attended 22 of them.
- **3 absent, 3 LOP** - the 22nd, 23rd and 29th. Right now **all three are
  unpaid**, because her leave has not been approved yet.
- **179.5 hours, 3.5 overtime** - the long day on the 17th.

The day-level view:

```
 2026-09-01 PRESENT  in 06:00:00 out 15:00:00  hrs 8.0   ot 0.0
 2026-09-17 PRESENT  in 06:00:00 out 18:30:00  hrs 11.5  ot 3.5
 2026-09-22 ABSENT   in   --     out   --      hrs 0.0   ot 0.0
 2026-09-29 ABSENT   in   --     out   --      hrs 0.0   ot 0.0
```

The 17th: 12.5 hours between punches, minus the 1-hour unpaid break = 11.5
worked, of which 3.5 is beyond the 8-hour shift.

### What to look for

| Field | If it is not zero |
|---|---|
| `invalidPunches` | Someone badged only once that day. **Fix these first** - a device error should not become an absence. |
| `absentDays` | Either genuinely absent, or leave that has not been approved yet. |
| `lateCount` / `earlyExitCount` | Arrival after the grace period / departure before shift end. |

---

## Step 8 — She applies for leave

```bash
curl -X POST http://localhost:8080/api/leaves -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"userId":"EMP005","leaveType":"CASUAL_LEAVE","fromDate":"2026-09-22","toDate":"2026-09-23","duration":"FULL_DAY","reason":"Family function"}'
```

```
 leave id=1  CASUAL_LEAVE  2026-09-22 to 2026-09-23  days=2.0  status=PENDING
             waiting on supervisor SUP001
```

**Note the `id` - you need it for the approval steps.**

The system refuses obviously wrong requests at this point rather than letting an
approver discover them: overlapping leave, a range spanning two calendar years,
a half day across multiple dates, or insufficient balance.

For a half day use `"duration":"FIRST_HALF"` or `"SECOND_HALF"` with the same
from and to date.

> A plain `EMPLOYEE` token can only file leave with `userId` equal to their
> own - filing "as" a coworker is rejected (404, same as any other
> not-yours-to-touch record). `HR001`/`ADMIN` may file on behalf of anyone; a
> `SUPERVISOR` may file on behalf of their own directly-supervised team.

---

## Step 9 — The supervisor sees it in their queue

```bash
curl http://localhost:8080/api/leaves/pending/SUP001 -H "Authorization: Bearer $TOKEN"
```

```
 1  Priya Kulkarni  2026-09-22 -> 2026-09-23  PENDING
```

---

## Step 10 — Supervisor endorses

```bash
curl -X POST http://localhost:8080/api/leaves/1/supervisor-approve -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"comments":"Cover arranged"}'
```

```
 status -> SUPERVISOR_APPROVED
```

**Balance has not moved yet.** This step is an endorsement, not the decision.
(`approverId` is not in the body above on purpose - see Step 0's note; it's
always the bearer token's own identity now, `HR001` in this walkthrough.)

---

## Step 11 — HR gives final approval

```bash
curl -X POST http://localhost:8080/api/leaves/1/approve -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"comments":"Approved"}'
```

```
 status -> APPROVED   approver HR001
```

**This is the step that consumes balance:**

```bash
curl "http://localhost:8080/api/leave-balances/EMP005?year=2026" -H "Authorization: Bearer $TOKEN"
```

```
 CASUAL_LEAVE       quota 12.0  used 2.0  available 10.0
 SICK_LEAVE         quota 8.0   used 0.0  available 8.0
 LEAVE_WITHOUT_PAY  quota 0.0   used 0.0  available 0.0
```

**Only a token belonging to `HR` or `ADMIN` can call this endpoint at all** -
`LEAVE_APPROVE` isn't granted to `EMPLOYEE` or `SUPERVISOR`, so an `EMP001`
token gets a flat `403` before the request body is even looked at.

| Action | Effect on balance |
|---|---|
| `/approve` | **Consumed** |
| `/reject` | Untouched |
| `/cancel` | **Restored**, if it had been approved |

---

## Step 12 — Attendance again, now that leave is approved

```bash
curl "http://localhost:8080/api/attendance/EMP005/monthly?month=2026-09" -H "Authorization: Bearer $TOKEN"
```

```
 workingDays 25 | presentDays 22.0 | leaveDays 2.0 | absentDays 1.0
 lopDays 1.0        <-- was 3.0 before approval
```

The two approved days moved out of absence and into paid leave. Only the 29th -
where she never applied - remains unpaid:

```
LOP = working days - present days - approved paid leave
    = 25 - 22 - 2
    = 1
```

**Settle every pending leave request before running payroll.** Anything still
`PENDING` or `SUPERVISOR_APPROVED` counts as absence and costs the employee a
day's pay.

---

## Step 12b — Generate the attendance payroll will pay from

Everything you have looked at so far was a preview computed from punches and
persisted nowhere. Payroll pays from a reviewed artifact, so generate it:

```bash
curl -X POST http://localhost:8080/api/attendance/generate -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"month":"2026-09","userIds":["EMP005"]}'
```

29 rows - 30 days minus the holiday on the 14th, which was never rostered.
Review them with `GET /api/attendance/EMP005/records?month=2026-09`.

If the device dropped a punch, correct the day rather than editing `device_logs`:

```bash
curl -X PUT http://localhost:8080/api/attendance/EMP005/2026-09-25 -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"firstIn":"2026-09-25T06:00:00","lastOut":"2026-09-25T15:00:00","remarks":"Device missed the exit punch"}'
```

The row becomes `MANUAL` and survives the next generation run. (Correcting or
unlocking someone else's company's attendance by naming their `userId` is
rejected with a 404, same as reading it - this was a real cross-tenant bug
found and fixed in an earlier audit pass, see SECURITY_AUDIT.md.)

---

## Step 13 — Run payroll

Skip step 12b and this returns 400: payroll refuses to run against attendance
nobody generated. Running it locks the month.

```bash
curl -X POST http://localhost:8080/api/payroll/generate -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"employeeId":"EMP005","month":9,"year":2026,"advanceDeduction":2000,"loanDeduction":0,"tds":0,"canteen":450,"bonus":1000,"incentive":0}'
```

You typed in only the manual amounts: advance ₹2,000, canteen ₹450, bonus
₹1,000. Everything else was derived.

```
 ATTENDANCE  workingDays 25   presentDays 22.0   paidLeave 2.0
             lopDays 1.0      payableDays 24.0
 RATES       perDay 1040.0    perHour 130.0      otHours 3.5

 EARNINGS                        DEDUCTIONS
   Basic + DA     12480.0          PF              1036.80
   HRA             4992.0          ESIC               0.00
   Conveyance      1248.0          Prof. Tax        200.00
   Education       1248.0          TDS                0.00
   Medical         1200.0          Advance         2000.00
   Other               0.0         Loan               0.00
   Overtime          455.0         Canteen          450.00
   Bonus            1000.0         ----------------------
   Incentive           0.0         TOTAL           3686.80
   ------------------------
   TOTAL           22623.0

 NET SALARY  18936.20
```

### Where each number came from

**Payable days = 24.** 25 working days minus 1 LOP day.

**Earnings are prorated 24/25:**

```
Basic + DA = 13000 x 24 / 25 = 12480
HRA        =  5200 x 24 / 25 =  4992
```

**Overtime = ₹455.** `perDay` = 26000 ÷ 25 = 1040. `perHour` = 1040 ÷ 8 = 130.
3.5 hours × 130 × 1.0 multiplier = 455. She gets this only because
`overtimeEligible` is `true`.

**PF = ₹1,036.80.** Her PF basic of 9000 is prorated to 8640 (24/25), then 12%.
PF is charged on the prorated basic, not the full one.

**ESIC = ₹0.** Her earned gross of 22623 is above the 21000 ceiling, so she is
outside the scheme.

**Professional tax = ₹200.** Gross 26000 is above the 10001 slab threshold.

**`lopDeduction` shows 882.00 but is not subtracted.** The earnings were already
reduced from 25 days to 24. Subtracting it again would charge her twice for the
same missing day - it appears on the slip purely so the employee can see what
the absent day cost.

To run everyone at once (uses zero for all manual deductions, so generate people
with advances individually):

```bash
curl -X POST "http://localhost:8080/api/payroll/generate-all?month=9&year=2026" -H "Authorization: Bearer $TOKEN"
```

---

## Step 14 — The salary slip

Open in a browser and print, or save as PDF (paste the token into the
`Authorization` header via a browser extension, or use `curl -o slip.html`
instead if your browser can't easily add headers to a plain navigation):

```
http://localhost:8080/api/salary-slips/EMP005/print?month=9&year=2026
```

Or as data:

```bash
curl "http://localhost:8080/api/salary-slips/EMP005?month=9&year=2026" -H "Authorization: Bearer $TOKEN"
```

```
 Accusharp Industries | Priya Kulkarni | September 2026 | rev 1
 net: 18936.20
 in words: Eighteen Thousand Nine Hundred and Thirty Six Rupees and Twenty Paise Only
```

The whole month as a spreadsheet:

```bash
curl -OJ "http://localhost:8080/api/salary-slips/export?month=9&year=2026" -H "Authorization: Bearer $TOKEN"
```

(A plain `EMPLOYEE` token can only ever fetch, print or appear in the export
of their own slip - Priya can read her own, not a coworker's, and the export
for an `EMPLOYEE` caller would come back containing only her own row.)

---

## Step 15 — Reports

```bash
curl "http://localhost:8080/api/reports/attendance/overtime?month=2026-09" -H "Authorization: Bearer $TOKEN"
```

```
 OT   EMP005  Priya Kulkarni  3.50 overtime hour(s)
 LOP  EMP005  Priya Kulkarni  1.0 LOP day(s)
 PF   EMP005  base 8640.0  amount 1036.8
```

Reports only aggregate what attendance and payroll already recorded - they never
recalculate, so a report can never disagree with a payslip. Reports require
`HR`, `ADMIN` or `SUPERVISOR` (never plain `EMPLOYEE`), and - unlike almost
everything else in this walkthrough - stay company-wide even for a
`SUPERVISOR` token, deliberately: these are aggregate reports, not individual
records.

Attendance reports take `month=yyyy-MM`; payroll reports take separate `month`
and `year` numbers.

---

## Step 16 — Correcting a mistake

The canteen amount should have been ₹300, not ₹450. Running generate again is
refused on purpose:

```bash
curl -X POST http://localhost:8080/api/payroll/generate -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"employeeId":"EMP005","month":9,"year":2026}'
```

```
 409  Payroll already generated for EMP005 for 9/2026 - use the regenerate endpoint
```

Use regenerate:

```bash
curl -X POST http://localhost:8080/api/payroll/regenerate -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" -d '{"employeeId":"EMP005","month":9,"year":2026,"advanceDeduction":2000,"canteen":300,"bonus":1000}'
```

```bash
curl "http://localhost:8080/api/payroll/employee/EMP005/revisions?month=9&year=2026" -H "Authorization: Bearer $TOKEN"
```

```
 rev 2  GENERATED   canteen 300.0  net 19086.20
 rev 1  SUPERSEDED  canteen 450.0  net 18936.20
```

**The old figure was not overwritten.** Revision 1 is preserved exactly as it
was, marked superseded. If Priya asks in March why September's slip said
₹18,936.20, the record is still there.

The same protection works the other way: every payroll row snapshots her salary
structure and the rule percentages at generation time, so giving her a raise in
October never changes September's payslip.

> **Numbers look wrong and you can't tell why?** `GET
> /api/payroll/debug?month=9&year=2026` returns every field the calculation
> used - attendance breakdown, per-day rate, every earning and deduction -
> plus a live comparison against Priya's *current* salary and the company's
> *current* `SalaryRule`. A `ruleDrifted: true` or `masterDataDrifted: true`
> means the rule or her salary changed after this payroll was generated -
> exactly the "why does this month look different" case. See
> [README.md §8](README.md#8-run-payroll).

---

## The monthly routine, condensed

Once set up, each month is six steps (plus logging in first - see Step 0):

| | Do this | Endpoint |
|---|---|---|
| 1 | Roster everyone | `POST /api/shift-schedules/bulk` |
| 2 | Let the device write punches all month | *(nothing to do)* |
| 3 | Clear every pending leave request | `GET /api/leaves?status=PENDING` |
| 4 | Generate the month's attendance | `POST /api/attendance/generate` |
| 5 | Review it and fix invalid punches | `GET /api/attendance/{userId}/records` then `PUT /api/attendance/{userId}/{date}` |
| 6 | Run payroll | `POST /api/payroll/generate-all` (or `POST /api/payroll/bulk-generate` with a CSV for per-employee bonus/incentive/deductions) |
| 7 | Print or export slips | `GET /api/salary-slips/export` |

**Steps 3 and 5 are the ones that cost people money if skipped.** An unfixed
invalid punch and an unapproved leave request both come out of someone's salary.

Do leave before generating: approving a leave after the fact means regenerating
the attendance to pick it up. Corrections made in step 5 are safe from that -
regeneration preserves them.

---

## Quick reference: the six things that trip people up

1. **You need a bearer token on everything.** Log in first (Step 0) - a
   forgotten `Authorization` header is a `401`, not a hint about what went
   wrong.
2. **`userId` must match the biometric device's user id.** Nothing else joins
   the two systems.
3. **No roster = no working days = everything is loss of pay.** Assign shifts
   before you look at attendance.
4. **Only final HR approval counts.** `SUPERVISOR_APPROVED` still costs the
   employee a day.
5. **Salary breakup is fixed at save time.** A salary rule change doesn't
   reach existing employees on its own - run
   `POST /api/employees/salary-structure/regenerate-all` after changing it.
6. **Use `/regenerate`, not `/generate`, to correct a month.** Generate is
   deliberately one-shot.
