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
it works internally see [ARCHITECTURE.md](ARCHITECTURE.md).

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

## Step 1 — See what already exists

A fresh database is pre-loaded with a company, two departments, two
designations, four shifts and four demo employees.

```bash
curl http://localhost:8080/api/companies
```

```bash
curl http://localhost:8080/api/departments
```

```bash
curl http://localhost:8080/api/designations
```

What came back:

```
 company  id=1 ACC    Accusharp Industries
 dept     id=1 PROD   Production
 dept     id=2 ADMIN  Administration
 desig    id=1 OPR    Machine Operator
 desig    id=2 MGR    Manager
```

**Write down the `id` values.** You need them when creating an employee.

Existing people, so you know who can approve things:

```bash
curl http://localhost:8080/api/employees
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
curl -X POST http://localhost:8080/api/designations -H 'Content-Type: application/json' -d '{"designationCode":"SR-OPR","designationName":"Senior Operator","description":"Senior grade"}'
```

```json
{ "id": 3, "designationCode": "SR-OPR", "designationName": "Senior Operator" }
```

Note **`id: 3`** - that is what we pass as `designationId` next.

---

## Step 3 — Add the employee

```bash
curl -X POST http://localhost:8080/api/employees -H 'Content-Type: application/json' -d '{"userId":"EMP005","employeeCode":"EMP-005","employeeName":"Priya Kulkarni","companyId":1,"departmentId":1,"designationId":3,"supervisorUserId":"SUP001","joiningDate":"2024-02-12","dateOfBirth":"1996-09-20","status":"PERMANENT","role":"EMPLOYEE","email":"priya@accusharp.example","phone":"9822001122","grossSalary":26000,"pfBasic":9000,"medicalAllowance":1250,"otherAllowance":0,"overtimeEligible":true}'
```

Result:

```
 created id=5  EMP005  Priya Kulkarni
 reports to    : SUP001 - Rakesh Patil
 YOU ENTERED   : gross=26000  pfBasic=9000  medical=1250  other=0
 SYSTEM DERIVED: basicDA=13000.0  hra=5200.0  conveyance=1300.0  education=1300.0
                 grossSalaryWage=22050.0
```

**This is the single most important thing to understand about employees.** You
entered four money fields. The system worked out the rest from the salary rule:

```
basicDA    = 26000 x 50%   = 13000
hra        = 13000 x 40%   =  5200
conveyance = 13000 x 10%   =  1300
education  = 13000 x 10%   =  1300
                             ------
           + medical 1250 + other 0
grossSalaryWage            = 22050
```

You cannot send `basicDA` or `hra` yourself - they are not on the request at
all. That is deliberate: it means a salary breakup can never be inconsistent
with the configured rule.

### Three fields that decide everything downstream

| Field | Why it matters |
|---|---|
| **`userId`** | Must equal the biometric device's user id. This is the key that joins device → attendance → leave → payroll. Get it wrong and she shows zero attendance forever. |
| **`status`** | `PERMANENT` = paid on working days minus LOP. `DAY_WISE` = paid only for days attended, over a fixed 26-day base. |
| **`overtimeEligible`** | Overtime hours are measured for everyone, but only **paid** if this is `true`. |

Also note **`supervisorUserId`** - without it, nobody can approve her leave and
no supervisor can schedule her.

> **Change the salary rule *before* adding people.** The breakup is calculated at
> the moment you save. Changing a percentage later does not touch anyone who
> already exists - you would have to re-save each employee. Check it first with
> `GET /api/salary-rules`.

---

## Step 4 — Add the month's holidays

```bash
curl -X POST http://localhost:8080/api/holidays -H 'Content-Type: application/json' -d '{"companyId":1,"holidayName":"Ganesh Chaturthi","holidayDate":"2026-09-14","optionalHoliday":false}'
```

A mandatory holiday is removed from working days, so it can never become loss of
pay. Set `optionalHoliday: true` for restricted holidays - those stay working
days unless someone actually takes leave.

---

## Step 5 — Roster her for the month

**This is the step people forget, and it silently ruins everything.** With no
roster there are no expected working days, so the employee reads as absent all
month and the whole salary becomes loss of pay.

```bash
curl -X POST http://localhost:8080/api/shift-schedules/bulk -H 'Content-Type: application/json' -d '{"userIds":["EMP005"],"fromDate":"2026-09-01","toDate":"2026-09-30","shiftCode":"MORNING","weekOffDays":["SUNDAY"],"skipHolidays":true,"overwriteExisting":true,"assignedBy":"SUP001"}'
```

Result:

```
 days scheduled : 29
 weekly offs    : 2026-09-06, 2026-09-13, 2026-09-20, 2026-09-27   (the Sundays)
 holiday skipped: 2026-09-14 is not in the roster at all
```

September has 30 days. One is the holiday (not scheduled), four are Sundays
(scheduled but flagged weekly off), leaving **25 actual working days**.

Check it visually:

```bash
curl "http://localhost:8080/api/shift-schedules/planner?month=2026-09&supervisorUserId=SUP001"
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

> If `assignedBy` is a supervisor, they can only schedule their own team.
> Try `"userId":"HR001","assignedBy":"SUP001"` and you get
> `400 Supervisor SUP001 does not manage employee HR001`.

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
curl "http://localhost:8080/api/attendance/EMP005/monthly?month=2026-09"
```

```
 workingDays 25 | presentDays 22.0 | absentDays 3.0 | leaveDays 0.0
 invalidPunches 0 | lateCount 0 | totalHours 179.5 | overtimeHours 3.5
 lopDays 3.0
```

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
curl -X POST http://localhost:8080/api/leaves -H 'Content-Type: application/json' -d '{"userId":"EMP005","leaveType":"CASUAL_LEAVE","fromDate":"2026-09-22","toDate":"2026-09-23","duration":"FULL_DAY","reason":"Family function"}'
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

---

## Step 9 — The supervisor sees it in their queue

```bash
curl http://localhost:8080/api/leaves/pending/SUP001
```

```
 1  Priya Kulkarni  2026-09-22 -> 2026-09-23  PENDING
```

---

## Step 10 — Supervisor endorses

```bash
curl -X POST http://localhost:8080/api/leaves/1/supervisor-approve -H 'Content-Type: application/json' -d '{"approverId":"SUP001","comments":"Cover arranged"}'
```

```
 status -> SUPERVISOR_APPROVED
```

**Balance has not moved yet.** This step is an endorsement, not the decision.

---

## Step 11 — HR gives final approval

```bash
curl -X POST http://localhost:8080/api/leaves/1/approve -H 'Content-Type: application/json' -d '{"approverId":"HR001","comments":"Approved"}'
```

```
 status -> APPROVED   approver HR001
```

**This is the step that consumes balance:**

```bash
curl "http://localhost:8080/api/leave-balances/EMP005?year=2026"
```

```
 CASUAL_LEAVE       quota 12.0  used 2.0  available 10.0
 SICK_LEAVE         quota 8.0   used 0.0  available 8.0
 LEAVE_WITHOUT_PAY  quota 0.0   used 0.0  available 0.0
```

Only `HR` or `ADMIN` can do this. Passing `"approverId":"EMP001"` is rejected.

| Action | Effect on balance |
|---|---|
| `/approve` | **Consumed** |
| `/reject` | Untouched |
| `/cancel` | **Restored**, if it had been approved |

---

## Step 12 — Attendance again, now that leave is approved

```bash
curl "http://localhost:8080/api/attendance/EMP005/monthly?month=2026-09"
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

## Step 13 — Run payroll

```bash
curl -X POST http://localhost:8080/api/payroll/generate -H 'Content-Type: application/json' -d '{"employeeId":"EMP005","month":9,"year":2026,"advanceDeduction":2000,"loanDeduction":0,"tds":0,"canteen":450,"bonus":1000,"incentive":0,"generatedBy":"HR001"}'
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
curl -X POST "http://localhost:8080/api/payroll/generate-all?month=9&year=2026&generatedBy=HR001"
```

---

## Step 14 — The salary slip

Open in a browser and print, or save as PDF:

```
http://localhost:8080/api/salary-slips/EMP005/print?month=9&year=2026
```

Or as data:

```bash
curl "http://localhost:8080/api/salary-slips/EMP005?month=9&year=2026"
```

```
 Accusharp Industries | Priya Kulkarni | September 2026 | rev 1
 net: 18936.20
 in words: Eighteen Thousand Nine Hundred and Thirty Six Rupees and Twenty Paise Only
```

The whole month as a spreadsheet:

```bash
curl -OJ "http://localhost:8080/api/salary-slips/export?month=9&year=2026"
```

---

## Step 15 — Reports

```bash
curl "http://localhost:8080/api/reports/attendance/overtime?month=2026-09"
```

```
 OT   EMP005  Priya Kulkarni  3.50 overtime hour(s)
 LOP  EMP005  Priya Kulkarni  1.0 LOP day(s)
 PF   EMP005  base 8640.0  amount 1036.8
```

Reports only aggregate what attendance and payroll already recorded - they never
recalculate, so a report can never disagree with a payslip.

Attendance reports take `month=yyyy-MM`; payroll reports take separate `month`
and `year` numbers.

---

## Step 16 — Correcting a mistake

The canteen amount should have been ₹300, not ₹450. Running generate again is
refused on purpose:

```bash
curl -X POST http://localhost:8080/api/payroll/generate -H 'Content-Type: application/json' -d '{"employeeId":"EMP005","month":9,"year":2026}'
```

```
 409  Payroll already generated for EMP005 for 9/2026 - use the regenerate endpoint
```

Use regenerate:

```bash
curl -X POST http://localhost:8080/api/payroll/regenerate -H 'Content-Type: application/json' -d '{"employeeId":"EMP005","month":9,"year":2026,"advanceDeduction":2000,"canteen":300,"bonus":1000,"generatedBy":"HR001"}'
```

```bash
curl "http://localhost:8080/api/payroll/employee/EMP005/revisions?month=9&year=2026"
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

---

## The monthly routine, condensed

Once set up, each month is six steps:

| | Do this | Endpoint |
|---|---|---|
| 1 | Roster everyone | `POST /api/shift-schedules/bulk` |
| 2 | Let the device write punches all month | *(nothing to do)* |
| 3 | Check attendance, fix invalid punches | `GET /api/attendance/{userId}/monthly` |
| 4 | Clear every pending leave request | `GET /api/leaves?status=PENDING` |
| 5 | Run payroll | `POST /api/payroll/generate-all` |
| 6 | Print or export slips | `GET /api/salary-slips/export` |

**Steps 3 and 4 are the ones that cost people money if skipped.** An unfixed
invalid punch and an unapproved leave request both come out of someone's salary.

---

## Quick reference: the five things that trip people up

1. **`userId` must match the biometric device's user id.** Nothing else joins
   the two systems.
2. **No roster = no working days = everything is loss of pay.** Assign shifts
   before you look at attendance.
3. **Only final HR approval counts.** `SUPERVISOR_APPROVED` still costs the
   employee a day.
4. **Salary breakup is fixed at save time.** Change the salary rule before
   adding people, not after.
5. **Use `/regenerate`, not `/generate`, to correct a month.** Generate is
   deliberately one-shot.
