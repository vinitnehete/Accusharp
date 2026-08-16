# Accusharp HRMS - Architecture & Design

How the system is built and why. For day-to-day operation see [README.md](README.md).
For the attendance engine in full - every rule, the punch windows, generation,
corrections and locking - see **[Attendance.md](Attendance.md)**; this file only
summarises it.

- **Stack**: Java 21, Spring Boot 4.1.0 (Web, Data JPA, Validation), MySQL, Lombok
- **Package root**: `com.accusharp.hrms`
- **Database**: MySQL schema `alsama`, Hibernate `ddl-auto=update`

## Layering

```
Controller  ->  Service  ->  Repository  ->  MySQL
   (REST)      (business      (Spring
               + calculation)  Data JPA)
```

Controllers bind and delegate; no business logic. Calculation lives in small
single-purpose services rather than inside the CRUD services, so a rule can be
changed and tested in one place.

```
config/       SecurityConfig (filter chain, CORS, password encoder), PermissionSeeder, startup seeding
controller/   REST endpoints
dto/          validated request payloads and response records
entity/       JPA entities
enums/        domain enums that carry rules, not just labels
exception/    ApiError + GlobalExceptionHandler
mapper/       entity -> response flattening
repository/   Spring Data JPA
security/     JwtService, RefreshTokenService, JwtAuthenticationFilter, UserPrincipal,
              CustomUserDetailsService, TenantContext, PermissionRegistry,
              AuthorizationService, RestAuthenticationEntryPoint, RestAccessDeniedHandler -
              see SECURITY.md, this package is the whole auth/authz/multi-tenancy story
service/
  calculation/  SalaryCalculationService, DeductionCalculationService,
                AttendanceCalculationService, LopCalculationService
  attendance/   AttendanceService
  shift/        ShiftService, ShiftSchedulingService
  leave/        LeaveService, LeaveBalanceService, LeaveCalculationService
  payroll/      PayrollService, SalarySlipService
  report/       ReportService, DashboardService
util/         AmountInWords, TemporaryPasswordGenerator,
              EmployeeCsvParser, ShiftAssignmentCsvParser, PayrollCsvParser (CSV -> request DTO,
              one independently-failable row at a time - see "Bulk / CSV mutation endpoints" below),
              EmployeeCredentialsCsvWriter (bulk-import's created employees -> a downloadable
              userId/employeeCode/employeeName/temporaryPassword sheet, see SECURITY.md)
```

Every failure returns one shape (`ApiError`: `timestamp`, `status`, `error`,
`message`, `path`) from a single `@RestControllerAdvice`.

## Business flow

```
Company -> Department -> Designation -> Employee -> Supervisor mapping
   -> Shift scheduling -> Biometric punches -> Attendance generated
   -> HR corrections -> Leave approval -> LOP -> Payroll -> Salary slip -> Reports
```

## Domain model

| Entity | Table | Notes |
|---|---|---|
| `Company` | `company` | The tenant. Every company-scoped entity below resolves back to one, directly or via its owning `Employee` |
| `Department`, `Designation`, `Category`, `Shift` | `department`, `designation`, `category`, `shift` | One row per company, plus rows with `company = null` - a shared, read-only-to-companies catalog (the seeded defaults). Unique on `(company_id, code)`, not code alone - see [SECURITY.md](SECURITY.md) Phase 6. `Category` is the employee grade (Worker, Supervisor, Manager, Director, ...) - a company may define as many as it needs via `/api/categories`, same as `Department`/`Designation` |
| `Employee` | `employee` | Business key `userId` (also the device user id); self-referencing `supervisor`; one of two principal types (see Security below). `category`, `gender`, `uanNo`, `esicIpNo`, `bankAccountNo` and `bankIfscNo` are all optional |
| `PlatformUser` | `platform_user` | The other principal type - platform-level accounts (company onboarding etc.), not tied to any company |
| `RefreshToken` | `refresh_token` | Opaque, hashed at rest, single-use with rotation - never a JWT itself |
| `Permission`, `RolePermission` | `permission`, `role_permission` | The data-driven grant table every `@PreAuthorize` check resolves against - see Security below |
| `AuditLog` | `audit_log` | Flat, scalar-only (no JPA relations, by design) - who did what, when, success or failure |
| `CustomRole`, `CustomRolePermission`, `EmployeeCustomRole` | `custom_role`, `custom_role_permission`, `employee_custom_role` | Company-defined roles (Phase 10) - a named bundle of permissions assignable to any number of employees, additive on top of their fixed `Role` |
| `ShiftSchedule` | `emp_attendance_shift` | One shift per employee per day (unique constraint) |
| `DeviceLog` | `device_logs` | Raw punches, written by the eSSL device. **Read-only here** |
| `DailyAttendance` | `emp_daily_attendance` | The reviewed attendance day payroll is paid from. Generated from punches, correctable by HR |
| `Holiday` | `holiday` | Company calendar; optional holidays stay working days |
| `LeaveRequest`, `LeaveBalance` | `leave_request`, `leave_balance` | Balance is always quota minus used |
| `MonthlyAttendanceSummary` | `emp_monthly_attendance_summary` | Cached rollup of the stored days |
| `SalaryRule` | `salary_rule` | One row per company holding every percentage and slab, plus one `company = null` global default a company falls back to until it customizes its own - see [SECURITY.md](SECURITY.md) |
| `SalaryRevision` | `salary_revision` | Append-only history of one employee's gross-salary changes (hike/promotion/correction) - see "Salary revision history" below |
| `Payroll` | `payroll` | Immutable snapshot per employee/month/year/revision |

### Salary structure

Derived on every employee write from the current `SalaryRule`, so it can never
drift from configuration. `grossSalaryWage` is never accepted from the API -
it is always the sum of the six components below, computed server-side.
`basicDA`, `hra`, `conveyanceAllowance` and `educationAllowance` are the same
way by default, but may be supplied directly on create (single or bulk CSV) -
see "Structure override at creation" below.

```
basicDA              = max(grossSalary x basicDaPercent, basicDaMinimumThreshold)
hra                  = basicDA x hraPercent              (default 40%)
conveyanceAllowance  = basicDA x conveyancePercent       (default 10%)
educationAllowance   = basicDA x educationPercent        (default 10%)
grossSalaryWage      = sum of all of the above + medical + other
```

`basicDaMinimumThreshold` is the government-notified minimum wage for
Basic+DA (default 50% of gross). It changes on its own schedule, independent
of the percentage, so it is a separate `SalaryRule` field rather than folded
into `basicDaPercent`. Zero (the default) disables the floor entirely -
existing companies are unaffected until they set one. Because HRA,
conveyance and education all derive from `basicDA`, they rise with it
whenever the threshold wins, exactly as if the percentage itself had
produced that higher figure.

#### Manual override and regeneration

`Employee.salaryStructureOverridden` is the escape hatch for the real-world
case a fixed formula never quite covers - a payslip that needs to differ from
what the percentages derive. `PUT /api/employees/{id}/salary-structure` sets
`basicDA`/`hra`/`conveyanceAllowance`/`educationAllowance` by hand and flips
the flag on; `SalaryCalculationService.applyCalculatedFields` then skips
re-deriving those four fields on every later employee write (a plain update,
or a rule change), leaving them exactly as set - only `grossSalaryWage` keeps
refreshing, since medical/other allowance can still change underneath it.

Because the derivation is skipped rather than never run, a `SalaryRule`
change is picked up automatically by every employee **not** overridden the
next time each is saved - but nothing pushes that recompute out on its own,
so a rule edit alone does not touch existing employees. Two endpoints force
it:

- `POST /api/employees/{id}/salary-structure/regenerate` - clears the
  override (if any) and recomputes one employee from their current gross
  salary and the company's current rule.
- `POST /api/employees/salary-structure/regenerate-all` - the same, for
  every active employee of the caller's company at once (the practical
  response to a rule change). Employees currently overridden are skipped -
  a bulk, rule-driven refresh silently discarding a deliberate per-employee
  override would be a surprise, not a fix.

Payroll itself needs no separate refresh: `PayrollService.build()` always
reads `basicDA`/`hra`/etc. straight off the `Employee` row at generate/
regenerate time, so once the employee's structure is corrected, the next
`POST /api/payroll/regenerate` for that period picks it up.

#### Structure override at creation

`PUT /api/employees/{id}/salary-structure` (above) is a two-step flow: create
with a derived structure, then override it afterward. A company onboarding
employees from an existing payroll system already knows the exact,
government-compliant breakup for each person and should not have to do
either step - `EmployeeRequest` (and `EmployeeCsvParser`'s CSV rows) accept
the same four fields directly on create. `EmployeeService.applyStructureOverride`
is the single choke point both the single-create and bulk-CSV-import
endpoints share: all four fields present sets them verbatim and flips
`salaryStructureOverridden`, all four absent leaves derivation exactly as
before, and anything in between - a structure that is part typed, part
rule-derived - is rejected outright, since it was never a real "fixed
structure" the caller could have intended. For bulk CSV import this rejection
is per-row, same as every other CSV validation failure: one bad row never
costs the rest of the file.

#### Salary revision history

Nothing previously recorded *why* or *when* an employee's `grossSalary`
changed - a plain `PUT /api/employees/{id}` just overwrote it, same as any
other field, with no trace of the old value. `SalaryRevision` (table
`salary_revision`) is the append-only audit trail for that, written by
`POST /api/employees/{id}/salary-revision`: `previousGrossSalary`,
`newGrossSalary`, a computed `hikePercent`, `effectiveDate`, `reason`
(`ANNUAL_INCREMENT`/`PROMOTION`/`MARKET_CORRECTION`/`OTHER`) and who applied
it. Like `AuditLog`, it stores `employeeId` as a plain scalar rather than a
JPA relation - a history record is a snapshot of what happened, not a live
view of current employee state (see `AuditLog`'s own Javadoc and
[SECURITY.md](SECURITY.md)).

The endpoint updates `grossSalary` and then runs the exact same
`SalaryCalculationService.applyCalculatedFields` re-derivation as any other
employee write - which means the same override interaction applies: an
employee with `salaryStructureOverridden = true` will not have `basicDA`/
`hra`/etc. move just because `grossSalary` did (derivation is skipped
entirely while overridden, per "Manual override and regeneration" above), so
`reviseSalary` requires the four replacement structure values in the same
request whenever the employee is currently overridden, rather than silently
leaving them stale. `GET /api/employees/{id}/salary-revisions` returns the
full history, newest `effectiveDate` first - the same self-service-scoped
visibility rule as the rest of the employee API (self, direct supervisor, or
HR/ADMIN).

This is a different gap from the "effective-dated salary rule versions" item
in **Not implemented** below - that would be a company's `SalaryRule`
percentages changing over time; `SalaryRevision` is one employee's actual pay
changing over time. Both are real, unrelated gaps.

## How the calculations work

### Attendance

The roster is the source of expectation: a day the employee was not scheduled on
is not an attendance day at all. For each scheduled day the engine takes the
punch window, pulls the punches inside it and derives first in, last out,
working hours, break, late minutes, early exit, overtime and invalid punches.

- **Night shifts.** A shift whose end time is not after its start time crosses
  midnight, so its window ends on the following calendar day - but the day still
  belongs to the shift's *start* date. This is handled once, in
  `AttendanceCalculationService`, so no caller has to think about midnight.
- **Punch window.** Asymmetric on purpose: it opens `entryWindowBufferMinutes`
  before the start (people badge in early, 60 by default) and closes
  `overtimeWindowMinutes` after the scheduled end (default 4 hours). A
  symmetric buffer would make a long overtime day look like a missing exit
  punch, so the closing side is configurable per shift and sized for real
  overtime.
- **Windows are exclusive.** A night shift's window crosses midnight and, with
  an overtime window on top, can reach into the hours the *next* scheduled day
  is already collecting for. A day's window is therefore truncated where the
  next scheduled day's window opens, so a punch is only ever counted by one day
  - otherwise a night shift swallows the next morning's entry as its own exit
  and both days claim it. It only ever shrinks: where shifts do not overlap the
  full overtime window survives, and consecutive night shifts never collide.
  The roster is read one day either side of the requested range, which is what
  makes the last night shift of a month hand over correctly to the next month.
- **Break.** With four or more punches the middle pairs are real in/out cycles,
  so the actual time outside is used. Otherwise the shift's configured unpaid
  break applies.
- **Day value.** `fullDayThresholdPercent` of the shift earns a full day (75%
  by default), `halfDayThresholdPercent` earns a half day (40% by default),
  below that is absent. One lone punch is an invalid punch (a device error),
  not an absence.
- **Holidays, weekly offs and approved leave** are layered on top, so *absent*
  only ever means "expected to work and did not".

All three configurable numbers above - `entryWindowBufferMinutes`,
`fullDayThresholdPercent`, `halfDayThresholdPercent` - live on
`AttendanceRule`, resolved in `AttendanceService` and passed into
`AttendanceCalculationService` alongside the `Shift`. It follows the exact
per-company + global-default pattern `SalaryRule` and `Holiday` already use
(see "Bulk / CSV mutation endpoints" below and `SalaryRuleService`'s
Javadoc) - everything else attendance calculation used to hardcode
identically for every company (shift timings, grace period, break minutes,
overtime window, weekly-offs, holidays) was already per-company via `Shift`
and `Holiday`; these three were the only genuinely global constants left.

### Attendance is generated, then reviewed

Attendance is a stored artifact, not a view that re-derives itself:

```
generate(month)  ->  one DailyAttendance row per rostered day    (GENERATED)
correct(day)     ->  HR fixes what the device got wrong          (MANUAL)
payroll          ->  reads the stored rows and freezes them      (locked)
```

Devices miss punches. When they do, the day reads as an invalid punch and
silently becomes loss of pay, so HR must be able to correct it - either by
supplying the punch times the device missed, or by declaring the day outright
when no punch exists at all. Corrected times are run back through
`AttendanceCalculationService`, the identical path a device punch takes, so a
hand-fixed day can never obey different rules from a machine-read one.

Two rules make corrections stick, and both are load-bearing:

- **A regeneration preserves `MANUAL` rows** unless `overwriteManual` is set, so
  a rerun picks up late-arriving punches without discarding HR's work.
- **A read never writes.** Before this, both a monthly GET and payroll itself
  recomputed from raw punches, so any correction was destroyed by the next
  request that happened to touch the month.

`recordStatus` (GENERATED / MANUAL) and `locked` are deliberately separate
fields: locking a day must not erase the fact that a human corrected it, which
is exactly the question asked when a salary is disputed months later.

Generating payroll locks the month, keeping an already-paid period
reproducible. Correcting it afterwards means unlock, fix, regenerate - which
supersedes the old revision rather than editing it.

Monthly summaries remain a cache, but now of the stored days rather than of raw
punches, so LOP and payroll inherit corrections automatically.

### Loss of pay

Never entered by hand:

```
LOP days = working days - present days - approved paid leave
```

Working days already exclude weekly offs and mandatory holidays, so those can
never become LOP. The specification's worked example - 26 working days, 23
attended, 2 days approved leave, 1 day LOP - is covered by
[`PayrollFlowIntegrationTest`](src/test/java/com/accusharp/hrms/PayrollFlowIntegrationTest.java).

### Payroll

Payroll is the final aggregation layer. It consumes employee master data,
attendance, leave, the holiday calendar, the shift roster and the salary rules,
and writes one record per employee per period.

Two payment models:

| | `DAY_WISE` | `PERMANENT` / `CONTRACT` / `INTERN` |
|---|---|---|
| Proration base | fixed payable days (26 by default) | the month's working days |
| Payable days | present + paid leave, capped at the base | working days minus LOP |
| LOP | not applicable - attendance *is* the pay | derived as above |

```
earn<component>  = component x payableDays / prorationBase
totalEarnings    = sum of earnings + bonus + incentive + overtime
totalDeduction   = PF + ESIC + PT + MLWF + TDS + advance + loan + canteen
netSalary        = totalEarnings - totalDeduction
```

- **PF** is deducted on the *prorated* basic (`earnPf`), not the full one. The
  full-month `pf` figure is reported for information only.
- **ESIC** applies only up to the configured wage ceiling.
- **Professional tax** follows the two-step slab in `SalaryRule`.
- **MLWF** (Labour Welfare Fund) is a single flat `SalaryRule.mlwfAmount`,
  deducted from the employee only in the June and December payroll runs
  (`Payroll.month == 6 || 12`) - zero every other month. Revised whenever the
  state notifies a new figure, same as every other `SalaryRule` field.
- **LOP deduction** is reported on the slip for transparency but is *not* added
  to the deduction total - the earnings were already prorated down by the same
  days, so adding it would deduct twice.
- **Overtime** is paid only to `overtimeEligible` employees, and the hours it's
  paid on depend on the same two payment models above: `PERMANENT`/`CONTRACT`/
  `INTERN` use the attendance engine's daily-summed value (each day measured
  against that day's own shift length); `DAY_WISE` has no fixed daily shift to
  measure against, so its overtime is the month's `totalHours` past the fixed
  monthly base instead - `dayWiseDaysInMonth x standardHoursPerDay` (208h at
  the defaults) - not a sum of daily excesses. Both feed the same
  `otAllowance = overtimeHours x perHour x overtimeRateMultiplier`.

### Immutable payroll history

Regenerating a period does not overwrite. The existing row is marked
`SUPERSEDED` and a new `revision` is inserted, so a slip printed months ago can
always be reproduced. Every payroll row snapshots the employee's salary
structure, the salary rule percentages and the attendance figures, so later
edits to any of them never change an already-generated month.

### Debugging a period: snapshot vs. live

`GET /api/payroll/debug?month=&year=` exists because the immutability above
cuts both ways: a payroll row is a faithful snapshot of what it was computed
from, which means it silently stops matching the employee master or the
`SalaryRule` the moment either changes afterward. Rather than re-deriving the
calculation by hand to find that out, `PayrollService.getPeriodDebugForCaller`
re-reads both live (the employee's current `grossSalary`/`pfBasic`, the
company's current rule percentages) and lays them next to what `Payroll`
actually stored, with `masterDataDrifted`/`ruleDrifted` booleans flagging a
mismatch. It changes nothing and triggers no recalculation - purely a read
juxtaposing snapshot against current state, gated behind the same
`PAYROLL_READ` permission and self-or-manages scoping as every other payroll
read.

### Bulk / CSV mutation endpoints

Four endpoints share one shape for driving a mutation from a frontend CSV
upload or a large JSON list, rather than one HTTP call per row:
`POST /api/employees/bulk-import`, `POST /api/shift-schedules/bulk/varied`
(+ its CSV sibling `/bulk/csv`), `POST /api/payroll/bulk-generate`, and
`POST /api/leaves/bulk-import`. Each parses independently-failable rows
(`EmployeeCsvParser`, `ShiftAssignmentCsvParser`, `PayrollCsvParser`,
`LeaveCsvParser` in `com.accusharp.hrms.util`), then runs every row through
the *same* single-row service call a non-bulk request would make -
`EmployeeService.create`, `ShiftSchedulingService.assign`,
`PayrollService.generate`/`regenerate`, `LeaveService.hrDirectCreate` - inside
its own try/catch, so a bad row never aborts the batch and never bypasses a
guard (admin-escalation, tenant scoping, supervisor-owns-team) a single call
would enforce. The uniform result, `BulkImportResult<T>` (`{totalRows,
successCount, failureCount, succeeded, errors}`), is what every one of them
returns.

`POST /api/employees/bulk-import` alone also accepts `?format=csv`, which
skips the JSON envelope and returns `EmployeeCredentialsCsvWriter`'s
downloadable credentials sheet for the rows that succeeded instead - see
[SECURITY.md](SECURITY.md) for why that exists (no email/SMS delivery
infrastructure to hand 50-500 temporary passwords out automatically).

`EmployeeCsvParser` and `PayrollCsvParser`'s numeric columns strip Excel-style
formatting (thousands separators, `₹`/`$`, stray whitespace) before parsing,
rather than surfacing commons-csv's raw `NumberFormatException` for a value
that's actually correct - `"41,000.00"` is exactly as valid an input as
`"41000.00"`.

Existing `POST /api/shift-schedules/bulk` (one shift, many employees, a date
range) is unrelated to this family - it stays a single validated operation
that 409s on the first conflict, by design (see `ShiftSchedulingService.assignBulk`).

## Leave workflow

```
Employee applies -> Supervisor endorses -> HR approves
```

Balance moves at exactly two points: it is consumed on final approval and
restored on cancellation. A rejection never touches it. Overlapping open or
approved requests are refused, half days are only valid on a single-day request,
and the balance is checked at application time so the approver never hits an
empty quota.

`LeaveService.hrDirectCreate` is a second entry point into the same terminal
state, not a second workflow: HR/ADMIN (`LEAVE_APPROVE`) skips application and
endorsement entirely for backfilling a day that already happened, but runs
the identical date/overlap/balance validations `apply` does - a direct entry
hard-blocks on insufficient balance rather than being allowed to silently
overdraw it - then consumes balance immediately, same as `approve`. `POST
/api/leaves/bulk-import` (`LeaveCsvParser`) is its CSV variant, same
independently-failable-row shape as the other bulk/CSV endpoints below.
`LeaveRequest.origin` (`SELF_SERVICE`/`HR_DIRECT`) is what distinguishes the
two once both sit at `APPROVED` - the leave-side analog of `DailyAttendance`'s
`GENERATED`/`MANUAL` `recordStatus`.

## Security &amp; multi-tenancy

Full detail lives in [SECURITY.md](SECURITY.md) (a phase-by-phase build log)
and [SECURITY_AUDIT.md](SECURITY_AUDIT.md) (an independent audit pass) - this
is the summary.

- **Two principal types.** An `Employee` (a company user - `ADMIN`, `HR`,
  `SUPERVISOR`, or `EMPLOYEE`) or a `PlatformUser` (`PLATFORM_OWNER`/
  `PLATFORM_ADMIN`, not tied to any company). Both authenticate through the
  same `POST /api/auth/login`, and the JWT carries which type of principal
  issued it.
- **Every `/api/**` endpoint** requires that JWT and a specific permission via
  `@PreAuthorize("@authz.can('...')")`, resolved from a data-driven
  `Permission`/`RolePermission` grant table (`PermissionRegistry`,
  `AuthorizationService`) rather than hardcoded `if (role == Role.HR)`
  checks - see SECURITY.md for the full matrix. Ownership-level rules (a
  supervisor may only schedule or approve their own team) remain enforced in
  the service layer beneath that, as defense in depth.
- **Multi-tenant isolation.** `TenantContext` resolves the caller's company
  from the JWT. Every single-resource lookup resolves its target through a
  tenant-checked choke point (`EmployeeService.getEntityById`/
  `getEntityByUserId` for most services; the same pattern independently on
  `Company`/`Department`/`Designation`/`Shift`/`Holiday`) and returns 404, not
  403, on a cross-company id - a 403 would confirm the record exists
  somewhere, a 404 does not.
- **"View only my own data" self-service scoping** (Phase 8): a plain
  `EMPLOYEE` sees only their own record across the employee directory,
  attendance, leave, leave balance, salary slips and shift roster; a
  `SUPERVISOR` sees themselves plus their own direct reports for the same
  set; `ADMIN`/`HR` are unrestricted within their own company.
  `ReportService`/`DashboardService` are a deliberate exception - those stay
  company-wide for SUPERVISOR/HR/ADMIN, since they're aggregate reports, not
  individual-record access.
- **Account security.** BCrypt password hashing, account lockout after
  repeated failed logins, no account-enumeration in login errors, short-lived
  JWT access tokens plus opaque rotating refresh tokens. Every newly created
  employee (not just a company's first admin during onboarding) is issued a
  one-time temporary password in the create response (Phase 9); if it's
  lost, ADMIN/HR can generate a new one via
  `POST /api/employees/{id}/reset-password` (also Phase 9) - there is still
  no *self-service* ("no admin involved") recovery flow, since that needs
  email delivery infrastructure this app doesn't have.
- **Audit trail.** Every security-sensitive write is recorded - login/
  logout/password events, employee/company/payroll changes (Phase 5), and,
  as of Phase 9, leave decisions, shift schedule writes, salary rule
  changes, attendance corrections and company status changes too.
  `GET /api/audit-logs/export` gives an unbounded CSV for a date range (the
  regular `GET` stays capped at 200 rows); `DELETE /api/audit-logs` purges
  old rows but is gated by a permission granted only to platform roles,
  never a company role - the entity a trail holds accountable must never be
  able to erase it.
- **Dynamic role/permission management** (Phase 10): a company `ADMIN` can
  define named custom roles, grant each an arbitrary set of permissions,
  and assign them to employees - additive on top of the employee's fixed
  `Role`, never a replacement for it, so every hardcoded `Role` check
  elsewhere in the app (self-escalation guard, supervisor-team rules,
  self-service scoping) is untouched by this. Platform-only permissions can
  never be granted through a custom role. See `CustomRoleController`/
  `CustomRoleService` and SECURITY.md's Phase 10 write-up.

## Design decisions worth knowing

- **Device integration is one-way.** `device_logs` is populated externally by the
  biometric device's middleware writing straight to MySQL. There is deliberately
  no endpoint to create or edit a punch - HR corrections are recorded on the
  generated attendance day instead, so the raw device reading survives next to
  the correction and a device re-sync can never clobber it.
- **No native SQL.** The one native MySQL query the previous attendance service
  used (`DATE_ADD`, `GROUP BY DATE(...)`) was replaced with portable derived
  queries plus grouping in Java, which is what lets the whole suite run on H2.
- **`month` and `year` are quoted** in the payroll and summary tables - they are
  reserved words on several databases. Backtick quoting renders as the target
  dialect's own quoting, so MySQL still sees the same column names.
- **Salary slips are HTML, not PDF.** The print view is self-contained HTML that
  any browser can print or save as PDF, which avoids a server-side PDF
  dependency. A true PDF/Excel renderer and email delivery remain open.

## Not implemented

Authentication, authorization, multi-tenant isolation, self-service
scoping, audit logging (now with full coverage and retention/export
tooling), and dynamic role/permission management all exist now (see
Security &amp; multi-tenancy above and [SECURITY.md](SECURITY.md)). Still
open: a self-service ("I forgot my password, no admin involved") recovery
flow - an ADMIN/HR-triggered reset exists instead, since there's no email
delivery infrastructure to build the self-service version on; a UI for
custom-role assignment (the API exists, see `CustomRoleController`); and a
general no-privilege-escalation check on custom roles (today only
platform-only permission codes are blocked, not "grant nothing beyond what
you yourself hold"). Also not built: multi-branch support, notification
and email services generally, effective-dated salary rule versions, soft
delete, Flyway migrations, Redis caching, and Swagger/OpenAPI documentation.
