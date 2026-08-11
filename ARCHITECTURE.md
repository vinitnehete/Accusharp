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
util/         AmountInWords, TemporaryPasswordGenerator
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
| `Department`, `Designation`, `Shift` | `department`, `designation`, `shift` | One row per company, plus rows with `company = null` - a shared, read-only-to-companies catalog (the seeded defaults). Unique on `(company_id, code)`, not code alone - see [SECURITY.md](SECURITY.md) Phase 6 |
| `Employee` | `employee` | Business key `userId` (also the device user id); self-referencing `supervisor`; one of two principal types (see Security below) |
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
| `Payroll` | `payroll` | Immutable snapshot per employee/month/year/revision |

### Salary structure

Derived on every employee write from the current `SalaryRule`, so it can never
drift from configuration. `basicDA`, `hra`, `conveyanceAllowance`,
`educationAllowance` and `grossSalaryWage` are **not** accepted from the API.

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
- **Punch window.** Asymmetric on purpose: it opens 60 minutes before the start
  (people badge in early) and closes `overtimeWindowMinutes` after the scheduled
  end (default 4 hours). A symmetric buffer would make a long overtime day look
  like a missing exit punch, so the closing side is configurable per shift and
  sized for real overtime.
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
- **Day value.** 75% of the shift earns a full day, 40% earns a half day, below
  that is absent. One lone punch is an invalid punch (a device error), not an
  absence.
- **Holidays, weekly offs and approved leave** are layered on top, so *absent*
  only ever means "expected to work and did not".

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
- **Overtime** is paid only to `overtimeEligible` employees, on hours the
  attendance engine measured against each day's own shift length.

### Immutable payroll history

Regenerating a period does not overwrite. The existing row is marked
`SUPERSEDED` and a new `revision` is inserted, so a slip printed months ago can
always be reproduced. Every payroll row snapshots the employee's salary
structure, the salary rule percentages and the attendance figures, so later
edits to any of them never change an already-generated month.

## Leave workflow

```
Employee applies -> Supervisor endorses -> HR approves
```

Balance moves at exactly two points: it is consumed on final approval and
restored on cancellation. A rejection never touches it. Overlapping open or
approved requests are refused, half days are only valid on a single-day request,
and the balance is checked at application time so the approver never hits an
empty quota.

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
