# Security - what exists today, and what is next

This covers **Phase 1** (authentication: login, password hashing, JWT),
**Phase 2** (authorization: permissions, `@PreAuthorize` on every business
endpoint), **Phase 3** (multi-tenant isolation: a company cannot reach
another company's data by id), **Phase 4** (platform company onboarding),
**Phase 5** (audit logging), **Phase 6** (per-company masters and
report/dashboard scoping), **Phase 7** (a full re-audit of every remaining
service, which found and fixed four more cross-company gaps), **Phase 8**
("view only my own data" self-service scoping), **Phase 9** (working
employee logins, admin-triggered password reset, full audit coverage, and
audit log retention/export), **Phase 10** (dynamic role/permission
management), **Phase 14** (reports and dashboard scoped to a supervisor's
team), **Phase 15** (custom roles effective end to end) and **Phase 16**
(data scope as a grantable permission), **Phase 17** (the admin is a company
account) and **Phase 18** (platform account names are reserved) of a multi-phase
security rollout. Read this
alongside [README.md](README.md) §13 and [ARCHITECTURE.md](ARCHITECTURE.md)
"Roles".

**[SECURITY_AUDIT.md](SECURITY_AUDIT.md)** is a full audit of everything
below, done after Phase 5 rather than assumed from these phase write-ups -
it found and fixed 2 severe cross-tenant IDORs this document had missed
(`Holiday` and `Attendance` write paths) and a privilege-escalation bug
(`Role.ADMIN` briefly held platform-only permissions). Read it alongside
this file, not instead of it - the phase-by-phase sections below are still
the source of truth for *what exists*; the audit is the record of *what was
independently checked and what was found wrong*.

## What exists

### Two principal types

- **`Employee`** - a company user. `userId` (e.g. `EMP001`, `HR001`) is the
  login username. Carries `passwordHash`, `accountEnabled`, `accountLocked`,
  `failedLoginAttempts`, `lastLoginAt`, and a single `role`
  (`ADMIN`/`HR`/`SUPERVISOR`/`EMPLOYEE`).
- **`PlatformUser`** - a platform-level account (company onboarding etc.), not
  tied to any company. Kept as its own entity rather than a "companyless"
  Employee, since Employee carries payroll/attendance fields that make no
  sense for platform staff. Role is `PLATFORM_OWNER` or `PLATFORM_ADMIN`.

### Login, refresh, logout, change-password

```
POST /api/auth/login            { "username": "EMP001", "password": "..." }
POST /api/auth/refresh          { "refreshToken": "..." }
POST /api/auth/logout           { "refreshToken": "..." }
POST /api/auth/change-password  { "currentPassword": "...", "newPassword": "..." }   (needs a bearer token)
```

- **Access tokens** are short-lived JWTs (15 min default), signed HS256,
  self-contained (subject, principal type, companyId, role) - validated
  without a database round trip on every request.
- **Refresh tokens** are opaque random values, **not** JWTs, hashed at rest,
  and rotated on every use (the old one is revoked). See
  `AuthApiHttpTest.refreshRotatesAndOldTokenIsRejected`.
- **Account lockout**: 5 failed attempts (`security.max-failed-login-attempts`)
  locks the account. No self-service or admin unlock endpoint yet.
- **No account enumeration**: an unknown username and a wrong password return
  the identical `401 Invalid username or password`.
- **Password change revokes all of that principal's refresh tokens.**

### Authorization: permissions, not hardcoded roles

Every business endpoint now requires authentication
(`anyRequest().authenticated()` in `SecurityConfig`) and carries
`@PreAuthorize("@authz.can('SOME_PERMISSION')")`. Permission resolution is
fully data-driven:

```
Permission        one grantable capability, e.g. EMPLOYEE_CREATE (33 total, see PermissionCode)
RolePermission     grants one Permission to one role name within one RoleScope (COMPANY or PLATFORM)
PermissionSeeder   seeds both tables on every startup, deleting and re-inserting RolePermission
                   so a narrowed grant actually narrows on restart, not just additive changes
PermissionRegistry loads all grants into memory once (after seeding), so @authz.can(...)
                   never hits the database
AuthorizationService  the "authz" bean every @PreAuthorize expression calls - one method, can(code)
```

A missing/invalid token → `401` (`RestAuthenticationEntryPoint`, rejected at
the filter chain before any controller runs). An authenticated principal
without the required permission → `403` - in practice always via
`GlobalExceptionHandler`'s `AccessDeniedException` handler, since a
`@PreAuthorize` denial is thrown *during* the controller method invocation
and Spring MVC's `@ExceptionHandler` resolution always gets first refusal
(verified empirically, not just reasoned about - see `SecurityConfig`'s
Javadoc). `RestAccessDeniedHandler` is kept for the different case of a
filter-level `authorizeHttpRequests().hasRole(...)` rule, which this app does
not currently use. Both produce the identical `ApiError` shape regardless.

**Employees and platform users each carry exactly one role today** (a plain
enum column) - there is deliberately no separate `UserRole` join table yet.
`RolePermission.roleName` is a plain string rather than a foreign key to an
enum specifically so that a future "custom company roles" feature (an admin
creating a named role and assigning permissions to it via API) can add rows
here without a schema change; no such management API exists yet, so today's
grants are fixed at startup by `PermissionSeeder`, not editable at runtime.

### The authorization matrix

| Permission | ADMIN | HR | SUPERVISOR | EMPLOYEE | PLATFORM_OWNER/ADMIN |
|---|---|---|---|---|---|
| `COMPANY_CREATE/UPDATE/DELETE` | - | - | - | - | ✓ |
| `COMPANY_READ` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `DEPARTMENT_MANAGE`, `DESIGNATION_MANAGE`, `CATEGORY_MANAGE`, `SHIFT_MANAGE`, `HOLIDAY_MANAGE` | ✓ | ✓ | - | - | - |
| `DEPARTMENT_READ`, `DESIGNATION_READ`, `CATEGORY_READ`, `SHIFT_READ`, `HOLIDAY_READ` | ✓ | ✓ | ✓ | ✓ | - |
| `EMPLOYEE_CREATE/UPDATE/DELETE` | ✓ | ✓ | - | - | - |
| `EMPLOYEE_READ` | ✓ | ✓ | ✓ | ✓ | - |
| `SHIFT_SCHEDULE_MANAGE` | ✓ | ✓ | ✓ (own team, enforced in service) | - | - |
| `SHIFT_SCHEDULE_READ`, `ATTENDANCE_READ` | ✓ | ✓ | ✓ | ✓ | - |
| `ATTENDANCE_GENERATE/CORRECT/UNLOCK` | ✓ | ✓ | - | - | - |
| `LEAVE_APPLY`, `LEAVE_READ`, `LEAVE_BALANCE_READ` | ✓ | ✓ | ✓ | ✓ | - |
| `LEAVE_SUPERVISOR_APPROVE` | ✓ | ✓ | ✓ (own team, enforced in service) | - | - |
| `LEAVE_APPROVE` (approve/reject/cancel) | ✓ | ✓ | - | - | - |
| `LEAVE_BALANCE_MANAGE`, `SALARY_RULE_READ/MANAGE`, `PAYROLL_PROCESS` | ✓ | ✓ | - | - | - |
| `WORK_POLICY_READ/MANAGE` (who is tracked, who is paid a fixed salary) | ✓ | ✓ | - | - | - |
| `PAYROLL_READ`, `REPORT_READ`, `DASHBOARD_READ` | ✓ | ✓ | ✓ | - | - |
| `SALARY_SLIP_READ` | ✓ | ✓ | ✓ | ✓ | - |

Full grant lists are in `PermissionSeeder`. Two gaps in the pre-existing
business logic were closed while building this matrix, not preserved:
`LeaveService.reject`/`cancel` previously had **no** role check at all
(unlike `approve`, which required HR/ADMIN) - both now require
`LEAVE_APPROVE`, same as approval.

### Actor identity is no longer client-supplied

Every endpoint that records "who did this" - `generatedBy`, `updatedBy`,
`approverId`, `assignedBy` on attendance, payroll, leave and shift-scheduling
requests - now has that field **overwritten with the authenticated
principal's username** in the controller before the service is called. The
request DTOs still declare (and validate) these fields for backward
compatibility with existing request shapes, but their client-supplied values
are ignored for real HTTP callers; only direct service-level tests (which
bypass the controller) still control them directly.

### Self-escalation guard

*Superseded by Phase 17:* this was once "granting ADMIN is ADMIN-only", enforced in
`EmployeeController`. A company now has exactly one admin, so `EmployeeService`
refuses the role to everyone - see "A company has one admin" below.

### Where things are

```
security/            JwtService, RefreshTokenService, JwtAuthenticationFilter,
                      UserPrincipal, CustomUserDetailsService,
                      RestAuthenticationEntryPoint, RestAccessDeniedHandler,
                      PermissionRegistry, AuthorizationService
config/SecurityConfig       Filter chain, CORS, password encoder, method security
config/PermissionSeeder     Seeds Permission + RolePermission
service/AuthService         Login/refresh/logout/change-password business logic
controller/AuthController
entity/{Employee,PlatformUser,RefreshToken,Permission,RolePermission}
```

### Environment variables

| Variable | Purpose | Default (dev only) |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | MySQL credentials | `root` / `root` |
| `JWT_SECRET` | HMAC signing key for access tokens, >= 256 bits | a fixed placeholder - **override this outside local dev, it is committed and public** |
| `CORS_ALLOWED_ORIGINS` | Comma-separated browser origins allowed to call the API | `http://localhost:3000,http://localhost:8081,http://localhost:19006` |

### Seeded credentials (demo data only)

When `hrms.seed.enabled=true` (the default), every seeded employee
(`HR001`, `SUP001`, `EMP001`, `EMP002`) and a platform account
(`platform_owner`) get the password **`Accusharp@123`**. Disable seeding
(`hrms.seed.enabled=false`) before loading real data - see README §13.

## Multi-tenant isolation (Phase 3)

### The choke point

`EmployeeService.getEntityById`/`getEntityByUserId` are where tenant
isolation actually lives. Almost every other service (attendance, leave,
leave balance, payroll, shift scheduling) already resolved the employee it
was operating on through one of these two methods before doing anything
else - so a single check there, comparing the target employee's company
against `TenantContext.currentCompanyId()`, protects all of them
transitively without touching their code. A cross-company id gets the
identical `404` an unknown one would (never `403`) - a `403` would confirm
the record exists somewhere else, which is itself information leakage.

`TenantContext` resolves the caller's company from the JWT-backed
`UserPrincipal` already on `SecurityContextHolder`. It resolves to "no
company to check against" - the tenant check then no-ops - for a platform
principal, an employee with no company of its own, or **no authenticated
principal at all**. That last case is deliberate: it is what every existing
service-level test hits (they call services directly, with no
`SecurityContext` populated), so none of them needed to change. In
production every request has already passed `SecurityConfig`'s
`anyRequest().authenticated()` by the time a service method runs, so the
check is fully live for real traffic.

### What else needed a matching fix

- `EmployeeController` create/update: `companyId` is overwritten from the
  caller's own company (same pattern as Phase 2's actor-identity fix) -
  otherwise HR at Company A could create, or reassign an existing employee
  to, Company B just by naming its id.
- `PayrollService.getById/getCurrent/getRevisions/getHistory` and
  `CompanyService.getById/getAll`: these read paths bypassed
  `EmployeeService` entirely (payroll) or have no natural choke point at all
  (company), so each got an explicit check.
- `EmployeeService.getActiveEntities()` - the base of every "generate for
  everyone" operation (`payroll/generate-all`, attendance's whole-company
  generate, the shift planner) - had no company filter at all. Without this
  fix, any HR could trigger payroll or attendance generation across *every*
  company on the platform, not just their own - a write-side-effect version
  of the same leak, and a more severe one than a misdirected read.
- `LeaveService`'s list queries (`getByStatus`, `getCalendar`,
  `getPendingFor`) have no company filter of their own, and route every row
  through the now-tenant-checked employee lookup to resolve a name. Left
  alone, a single other-company row mixed into the result would throw and
  take the *whole list* down instead of just being excluded - not
  "isolated," just broken. Fixed with a filtering wrapper
  (`toResponseIfAccessible`) that drops inaccessible rows instead of
  propagating the exception.
- `SalaryRule` was a single global row every company shared - a real
  multi-tenancy bug (Section 19 of the original spec), not just an
  access-control gap. Now one row per company plus a `company = null`
  global default every company falls back to until it customizes its own;
  `updateRule` only ever creates/edits the *caller's own* company's row.

### A bug found while building this, unrelated to tenant isolation itself

Adding `SalaryRule.company` (a lazy `@ManyToOne`) exposed that this app's
production setting `spring.jpa.open-in-view=false` (correctly set in
`application.properties`, but never carried into `src/test/resources/`,
so no test had ever run against it) makes returning an entity with an
uninitialized lazy association directly from a controller throw
`LazyInitializationException` → `500`, instead of the graceful "session
still open" behavior Spring Boot's open-in-view *default* (`true`) would
have given it. `SalaryRuleController` and the pre-existing
`HolidayController` both return raw entities with a lazy `company` field;
both are now `@JsonIgnore`d on that field. Test properties now set
`open-in-view=false` to match production, so this class of bug fails loudly
in CI instead of silently passing.

## Platform company onboarding (Phase 4)

```
POST /api/companies/onboard   PLATFORM_OWNER / PLATFORM_ADMIN only
```

A bare `POST /api/companies` (still available, unchanged) leaves a company
with zero employees - and therefore nobody able to create one, since
`EMPLOYEE_CREATE` is company-scoped and held only by that company's own
ADMIN/HR, who don't exist yet. `/onboard` creates the `Company` and its
first `ADMIN`-role employee together, atomically, with a server-generated
temporary password returned exactly once in the response body -
`CompanyOnboardingService` never logs it. The platform operator relays it
out of band; the new admin should change it via `POST /api/auth/change-password`
on first login (there is no forced-change flag yet - seed-worthy follow-up).

**A real bug this uncovered**: `Role.ADMIN` (company-scoped) had been seeded
with `EnumSet.allOf(PermissionCode.class)` since Phase 2 - literally every
permission, including the platform-only `COMPANY_CREATE/UPDATE/DELETE` -
directly contradicting this file's own authorization matrix, which always
showed those as platform-only. Nothing had ever exercised "a company admin
attempts a platform action" until `CompanyOnboardingHttpTest.onboardingIsPlatformOnly`.
Fixed by having `ADMIN` and `HR` share one literal permission set in
`PermissionSeeder` instead of `ADMIN` being independently (and wrongly)
defined as "all of them" - the two are functionally identical everywhere in
this app's rules except that platform carve-out, so sharing the set makes
the correct behavior structural rather than something to remember to keep
in sync.

## Audit logging (Phase 5)

```
GET /api/audit-logs?limit=50   ADMIN, PLATFORM_OWNER, PLATFORM_ADMIN only - not HR
```

`AuditLog` is a flat, scalar-only table (no JPA relations - see its Javadoc
for why: a `@ManyToOne` here would reintroduce the exact lazy-serialization
bug fixed on `SalaryRule`/`Holiday` in Phase 3). Every write goes through
`AuditService`, always `@Transactional(propagation = REQUIRES_NEW)` - an
audit record has to commit independently of the business operation it
describes, both because a `FAILURE` row is written by definition inside a
transaction that is about to roll back, and because a trail an unrelated
later failure could silently erase is not a trail.

Covered today: login success/failure (including unknown usernames and
locked-account attempts), logout, password change, employee create/update/
deactivate, company onboarding, payroll generation. Not yet covered: leave
approval/rejection, shift schedule changes, salary rule changes,
attendance corrections - the highest-value events were prioritized over
full coverage given this phase's scope; extending `AuditService.record(...)`
into any of these follows the exact same one-line pattern used everywhere
above.

`GET /api/audit-logs` is company-scoped the same way every other read is
(`TenantContext`) - a company `ADMIN` sees only their own company's trail,
platform accounts see everything. Deliberately **not** granted to `HR`,
unlike every other permission `ADMIN` and `HR` share - HR's own actions are
exactly what the trail needs to hold HR accountable for, so HR reviewing it
would be self-auditing.

**Never put a password, token, or secret in an audit `detail` field** - it
is stored in plain text and returned verbatim by the read endpoint.

## Multi-tenant masters and reports (Phase 6)

Closes the two findings [SECURITY_AUDIT.md](SECURITY_AUDIT.md) left open
after the Phase 5 audit - both needed a decision Phase 5 didn't have
authority to make alone; see that file's history for why they were left
open rather than rushed.

### `Department`, `Designation`, `Shift` are now per-company

Each of the three now carries a nullable `company` (`@ManyToOne`,
`@JsonIgnore` - same pattern as `SalaryRule.company`/`Holiday.company`), and
their single-column unique constraint (`department_code` etc.) became a
composite `(company_id, code)` one, mirroring `Holiday`'s shape.

Unlike `SalaryRule` - a singleton settings row every company falls back to
until it customizes its own - these three are *lists*, so `company = null`
means something different here: a **shared, read-only-to-companies**
catalog (today, exactly `DataSeeder`'s standard four shifts and demo
departments/designations), not a "default until overridden." Concretely:

- **Read** (`getById`/`getAll`): a company sees its own rows plus every
  shared row. A cross-company row (belonging to a *different* company, not
  the shared catalog) 404s, same as every other tenant check in this app.
- **Write** (`create`/`update`/`delete`): only the caller's own company's
  rows are writable - a company can never edit or delete a shared row,
  closing the exact gap [SECURITY_AUDIT.md](SECURITY_AUDIT.md) flagged: an
  in-place edit to a shared `Shift`'s times used to be completely
  unguarded and would have silently corrupted every company's attendance
  the next time it was calculated. `create` still checks for a code
  collision against both the caller's own rows and the shared catalog, so a
  company can't shadow a shared code with a private one of the same name.
- `EmployeeService.create`/`update` already resolved `departmentId`/
  `designationId` through `DepartmentService.getById`/
  `DesignationService.getById` - so the moment those carry the tenant check,
  an employee can no longer be assigned another company's private
  department or designation, with zero changes to `EmployeeService` itself.
  Same choke-point-inheritance pattern Phase 3 established.
- `ShiftService.getByCode` gained a `companyId` parameter (mirrors
  `HolidayService.mandatoryHolidayDates`): the caller's own company's shift
  with that code, falling back to the shared catalog. `ShiftSchedulingService`
  threads the same `companyId` it already computed for holiday resolution
  through to this too.

**Schema note:** `ddl-auto=update` adds the new `company_id` column to an
existing database automatically, but cannot drop the old single-column
unique index - see
[`docs/migrations/2026-08-08-per-company-masters.sql`](docs/migrations/2026-08-08-per-company-masters.sql)
for the manual `ALTER TABLE` steps any pre-Phase-6 database needs (not
required for a fresh install or the test suite, which build the schema from
the entities directly).

**Proof:** `TenantIsolationHttpTest.departmentCrossTenantAccessIsRejected`,
`.shiftCrossTenantEditIsRejected`.

### List/report endpoints stop leaking cross-company data

`EmployeeService.getAll()` (new `getAllEntities()`, the same
`tenantContext.currentCompanyId()`-scoped shape as `getActiveEntities()` but
without the active-only filter - deactivated employees' payroll history must
stay visible to their own company) and `PayrollService.getPeriod()` (filters
by the caller's company's employee `userId`s, since `Payroll` has no
`company_id` column of its own) are now scoped. Every `ReportService` method
that calls `payrollService.getPeriod` (`payrollReport`, `pfReport`,
`professionalTaxReport`, `esicReport`, `departmentPayrollReport`,
`companyPayrollReport`) inherited the fix for free, the same
choke-point pattern `EmployeeService.getEntityById` gave the rest of this
app in Phase 3.

`DashboardService` had six separate unscoped spots - fixed by reusing data
the method already had scoped rather than adding new queries: employee and
payroll counts now come from the already-scoped `active` list and
`PayrollService.getPeriod` (replacing direct `PayrollRepository` calls
entirely), and pending-leave counts, today's-leave, leave-usage, birthdays
and anniversaries are now filtered against the caller's company's employee
`userId`s before being counted or returned.

**Proof:** `TenantIsolationHttpTest.payrollReportIsScopedPerCompany`.

## Full-surface re-audit (Phase 7)

After Phase 6, every service and controller in the app was re-audited from
scratch (not by re-reading this file) specifically for cross-company leaks
and, separately, for "does a caller need to own the record it's touching."
Four real cross-company gaps were found and fixed; a full inventory of the
remaining, larger "view only my own data" intra-company gap is now recorded
below for the next phase to close.

### `ShiftSchedulingService.deleteRange` had no tenant or team check at all

Unlike every other mutating method in the class, `deleteRange` never
resolved `userId` through `EmployeeService` and never called
`assertMaySchedule`. Any HR/ADMIN/SUPERVISOR in any company could delete
another company's employee's shift schedule for an arbitrary date range -
and a SUPERVISOR could do it outside their own team too, bypassing the
"own team only" rule every sibling method enforces. Fixed by adding both
checks, matching `assign`/`assignBulk`/`autoRotate`/`copyMonth`/`swap`
exactly; `assignedBy` is now threaded through
`DELETE /api/shift-schedules/{userId}` the same way it already was for
every `POST` in this controller.

### `LeaveBalanceService.setQuota` had no tenant check anywhere in its call chain

`getOrCreate` is a raw, unscoped repository lookup by `userId`; every other
caller of it (`getBalances`, `consume`, `restore`) is reached only after an
upstream tenant check already ran, but `setQuota` had none of its own. Any
HR/ADMIN could permanently overwrite another company's employee's leave
quota via `PUT /api/leave-balances/{userId}`. Fixed with an
`employeeService.getEntityByUserId(userId)` check, matching `getBalances`.

### Two "mutate-then-check" ordering gaps, hardened

`LeaveService.approve/reject/cancel/supervisorApprove` (the ADMIN/HR branch)
and `PayrollService.regenerate` all resolved and mutated their target
record *before* tenant-checking it - the check only ever ran at the very
end (inside `toResponse()`/`build()`), by which point a balance had already
been consumed/restored or a payroll row already marked `SUPERSEDED` and
saved. This was not independently exploitable - each method is one
`@Transactional` block, and the terminal `NotFoundException` rolled the
whole thing back - but it was correct by accident (dependent on default
rollback-on-unchecked-exception) rather than by the checked-then-mutate
choke-point pattern used everywhere else. Both now check first,
unconditionally, before touching anything: `LeaveService` via a new
`assertTargetAccessible` helper called first in all four decision methods,
`PayrollService.regenerate` via `employeeService.getEntityByUserId` as its
first line.

### `PayrollService.generate` leaked one bit cross-company via 409 vs 404

The "already generated this period" existence check ran *before* the
tenant check inside `build()`, so a cross-company `employeeId` that already
had payroll generated got a distinguishable `409` instead of the `404`
every other cross-company attempt gets - confirming a fact about another
company's payroll state. Fixed by moving the tenant check to the top of
`generate()`, before the existence check.

**Proof:** `TenantIsolationHttpTest.shiftScheduleDeleteRangeCrossTenantIsRejected`,
`.leaveBalanceSetQuotaCrossTenantIsRejected`, `.leaveDecisionCrossTenantIsRejected`,
`.payrollGenerateCrossTenantIsRejected`.

### What Phase 7 also confirmed clean

Every other Phase 1-6 fix was re-verified intact with no missed call site:
`Employee`/`Company`/`Department`/`Designation`/`Shift`/`Holiday`/`SalaryRule`/
`AuditLog` access, `CompanyOnboardingService`, every `AttendanceService` and
remaining `ShiftSchedulingService` method, every `ReportService`/
`DashboardService` method, and `SalarySlipService` (routes entirely through
the now-scoped `PayrollService`). JWT-embedded `companyId`/`role` are
trusted for the access token's life with no DB re-check by design (same
short-expiry tradeoff already documented for disabled/locked accounts); a
refresh cycle re-fetches fresh from the database and self-heals.

### The bigger remaining gap: "view only my own data"

Confirmed and catalogued, not fixed in this phase - see [Not yet
built](#not-yet-built-next-phases) below. In short: `EMPLOYEE_READ`,
`ATTENDANCE_READ`, `SHIFT_SCHEDULE_READ`, `LEAVE_READ`,
`LEAVE_BALANCE_READ`, and `SALARY_SLIP_READ` are all granted to plain
`Role.EMPLOYEE`, and none of the methods they gate restrict the target to
the caller's own record (or, for `SUPERVISOR`, their own team). Widest
blast radius: `SalarySlipController` (any employee can read or bulk-export
any coworker's full payslip) and `EmployeeController.getTeamOf` (any
employee can bulk-read a whole team's compensation by naming any
`supervisorUserId`). This needs a product decision on shape, not just code
- see the next-phases entry.

## "View only my own data" self-service scoping (Phase 8)

Closes the gap Phase 7 catalogued but deliberately didn't fix without a
product decision. Confirmed shape:

- **Plain `EMPLOYEE`**: sees only their own record - no coworker directory,
  no other employee's attendance, leave, leave balance, salary slip, or
  shift roster, by id or by list.
- **`SUPERVISOR`**: sees themselves plus their own direct reports for the
  same set of endpoints - not the whole company.
- **`ADMIN`/`HR`**: unrestricted within their own company, unchanged.
- **`ReportService`/`DashboardService` (`REPORT_READ`/`DASHBOARD_READ`)
  deliberately excluded**: these stay company-wide for SUPERVISOR/HR/ADMIN
  exactly as before - they're aggregate reports, not individual-record
  access, and restricting them wasn't part of what this phase was asked to
  fix. **Superseded by Phase 14**: reports and the dashboard are now scoped
  the same way.

### The mechanism: one pair of methods on `EmployeeService`

`assertSelfOrManages(targetUserId)` (throws, 404 not 403 - same rationale
as `assertAccessible`) and `isSelfOrManages(targetUserId)` (non-throwing,
for filtering a list) both resolve to: the caller is the target themselves,
or a `SUPERVISOR` who directly supervises the target (via the existing
`supervises()` check already used for scheduling/approvals), or `ADMIN`/HR.
No-ops under the same conditions `assertAccessible` no-ops under (no
principal, a platform principal) - so every existing service-level test
that calls services directly with no `SecurityContext` is unaffected; all
80 pre-existing tests passed unchanged.

Every single-record read added one line right after the tenant check it
already had: `AttendanceService` (`getDailyAttendance`/
`getMonthlyAttendance`/`getRecords`), `ShiftSchedulingService.getRoster`,
`LeaveBalanceService.getBalances`, `PayrollService` (`getById`/
`getCurrent`/`getRevisions`/`getHistory`), and `EmployeeService`
(`getById`/`getByUserId`). `LeaveService` needed only one change, in
`toResponse()` - every read method (`getById`, `getHistory`,
`getPendingFor`, `getByStatus`, `getCalendar`) already funnels through it,
and the existing `toResponseIfAccessible` filter-not-propagate wrapper
(Phase 3) already turns the resulting `NotFoundException` into "drop this
row" for the list endpoints, for free.

### List/bulk endpoints needed their own logic, not just the one-liner

- `EmployeeService.getVisible()` (new) - the self-service-restricted list
  `EmployeeController.getAll()` now calls, filtering `getAllEntities()` by
  `isSelfOrManages`. Deliberately **not** the same method as
  `EmployeeService.getAll()`, which `ReportController.employeeReport()`
  still calls unrestricted - see the reports exclusion above.
- `EmployeeService.getTeamOf` gained `assertSelfOrManages(supervisorUserId)`
  - only that supervisor, or ADMIN/HR, may fetch a team roster.
- `EmployeeService.plannerScope(requestedSupervisorUserId)` (new) replaces
  the ad-hoc filter `ShiftSchedulingService.getMonthlyPlanner` used to build
  inline: the requested `supervisorUserId` is trusted as-is only for
  ADMIN/HR (unchanged behavior); a `SUPERVISOR`'s request is silently
  forced to their own team regardless of what was asked for (same
  overwrite-not-reject pattern as `companyId` elsewhere in this app); a
  plain `EMPLOYEE` always gets a planner of exactly themselves. Before this
  fix, omitting `supervisorUserId` entirely handed back the whole company's
  roster to any caller.
- `LeaveService.apply` gained `assertSelfOrManages(payload.getUserId())` -
  blocks a plain `EMPLOYEE` filing leave as an unrelated coworker, while
  still letting a `SUPERVISOR` file on behalf of their own team (a
  legitimate use, e.g. an employee who called in sick without portal
  access) and `ADMIN`/HR file for anyone.
- `PayrollService.getPeriodForCaller` (new) - `getPeriod` itself is left
  untouched and still company-wide (every `ReportService` caller keeps
  using it directly, per the reports exclusion above); the new method adds
  the `isSelfOrManages` filter on top, for the two places that needed it:
  `PayrollController.getPeriod` and `SalarySlipService`'s two period
  methods (`getSlipsForPeriod`, `renderPeriodCsv` - the CSV bulk-export the
  Phase 7 audit called out as the widest blast radius of any endpoint
  reviewed).

**Proof:** `SelfServiceScopingHttpTest` (new, 10 tests) - one company, an
`EMPLOYEE`, a `SUPERVISOR`, and the `SUPERVISOR`'s direct report, over real
HTTP, covering the employee directory, team roster, attendance, shift
roster, leave apply/read, leave balance, salary slips, and the
supervisor-filtered payroll period list.

## Working employee logins, full audit coverage, and retention (Phase 9)

### Every new employee gets a working login

Found while setting up [docs/testing/multi-company-smoke-test.sh](docs/testing/multi-company-smoke-test.sh)
to actually onboard and use two companies end to end - not a security
finding, a functional one, but one that blocked real usage outright:
`EmployeeService.create()` never set a `passwordHash` at all. Only
`CompanyOnboardingService`'s one-time bootstrap step generated a password
(for a new company's first `ADMIN`). Every employee HR/ADMIN created
afterward through the ordinary `POST /api/employees` had no password and
could never log in - permanently, since there was no way to recover from
it (see the password reset feature immediately below, added in this same
phase to close exactly that gap).

**Fix:** `EmployeeService.create()` now generates a temporary password the
same way onboarding always has - same generator
(`TemporaryPasswordGenerator`, extracted so both call sites share the exact
algorithm rather than duplicating it), same one-time-return contract
(`EmployeeCreationResponse{employee, temporaryPassword}`, never logged, the
employee changes it via the existing `POST /api/auth/change-password`).
`PUT /api/employees/{id}` (updating an existing employee) is unaffected -
only creation needed this.

**Response shape change:** `POST /api/employees` now returns `{"employee":
{...}, "temporaryPassword": "..."}` instead of a bare employee object - the
same shape `POST /api/companies/onboard` already used. Every other
employee endpoint (`GET`, `PUT`) is unchanged.

**Proof:** `CompanyOnboardingHttpTest.onboardingCreatesCompanyAndWorkingAdmin`
now also creates a second employee and logs in as them with the returned
password; the smoke test script does the same against a live server plus
proves the new supervisor/employee immediately exercise Phase 8's
self-service scoping correctly (own team visible, coworkers not,
impersonation blocked).

### Admin-triggered password reset

The practical stand-in for self-service forgot-password - this app has no
email delivery infrastructure to build the real thing on top of.
`POST /api/employees/{id}/reset-password` (`EMPLOYEE_UPDATE`, same
permission as every other account-affecting change to an employee)
generates a fresh one-time temporary password with the same
`EmployeeCreationResponse` contract as creation, and also clears any
failed-login lockout and revokes every existing refresh token for that
principal - a reset that left the account locked, or an old session still
valid, would not be a real recovery path.

**Proof:** `CompanyOnboardingHttpTest.adminCanResetAnEmployeesPassword` -
the old password stops working, the new one logs in.
`TenantIsolationHttpTest.employeePasswordResetCrossTenantIsRejected` -
Company A's HR cannot reset Company B's employee's password.

### Full audit coverage of every sensitive action

SECURITY.md's Phase 5 write-up named leave approval/rejection, shift
schedule changes, salary rule changes, and attendance corrections as
explicitly not yet covered. All four now record one audit row per API call
(not per affected record, so a bulk shift assignment across 50 employees
is one row, matching the granularity `EMPLOYEE_CREATE`/`COMPANY_ONBOARD`
already used): `LeaveService` (`LEAVE_SUPERVISOR_APPROVE`, `LEAVE_APPROVE`,
`LEAVE_REJECT`, `LEAVE_CANCEL`), `SalaryRuleService.updateRule`
(`SALARY_RULE_UPDATE`), `AttendanceService` (`ATTENDANCE_CORRECT`,
`ATTENDANCE_UNLOCK`), and every `ShiftSchedulingService` write (assign,
bulk-assign, auto-rotate, copy-month, holiday-override, swap,
delete-range). `CompanyService.update` also now records
`COMPANY_STATUS_CHANGE` whenever a company's status actually changes -
closing the "revisit only if a dedicated audit trail per status change is
needed" note the "Not yet built" list carried for company activate/
deactivate; `PUT /api/companies/{id}` remains the only endpoint, no new one
was needed.

**Proof:** `AuditLogHttpTest.salaryRuleChangeIsAudited`,
`CompanyOnboardingHttpTest.companyStatusChangeIsAudited`.

### Audit log retention and export

Previously an unbounded table with no archival or deletion policy.
`GET /api/audit-logs/export?fromDate=&toDate=` (`AUDIT_READ`, same
permission as the existing capped `GET`) returns an unbounded CSV for a
date range - the existing `GET /api/audit-logs?limit=` stays capped at 200
rows, which was never meant for archival. `DELETE /api/audit-logs?beforeDate=`
purges rows older than a date, gated by a **new `AUDIT_MANAGE` permission
granted only to platform roles, never a company role** - the entity an
audit trail holds accountable must never be able to erase it, not even a
company's own `ADMIN`. The purge itself is recorded as its own audit row,
written *after* the delete completes, so it can never retroactively delete
itself.

**Proof:** `AuditLogHttpTest.exportProducesCsv`,
`AuditLogHttpTest.purgeIsPlatformOnly` (a company `ADMIN` gets 403),
`CompanyOnboardingHttpTest.platformCanPurgeAuditLog`.

## Dynamic role/permission management (Phase 10)

The single largest remaining item on the "not yet built" list - closed as
an **additive** layer on top of the existing `Role` enum, not a
replacement for it. The alternative (migrating `Employee.role` itself to a
relational assignment) would have meant touching every hardcoded `Role`
check across the app (the self-escalation guard, supervisor-team rules,
self-service scoping's `isVisibleTo`) with real correctness risk for a
comparatively narrow benefit; the additive design leaves every one of
those checks completely untouched.

**What a company ADMIN can now do:** create a named custom role
(`POST /api/roles`), grant it any set of permissions
(`PUT /api/roles/{id}/permissions`), and assign it to any number of that
company's employees (`POST /api/roles/{id}/employees/{userId}`) - on top
of, never instead of, that employee's fixed `Role`. `ROLE_MANAGE`/
`ROLE_READ` are ADMIN-only, same trust bar as `AUDIT_READ` (not shared
with HR).

**How it actually takes effect - `AuthorizationService.can()` gained a
fallback:** the fast path (checking the principal's base `Role` against
`PermissionRegistry`'s in-memory, fixed-at-startup cache) is completely
unchanged. Only if that check fails, and the principal is a company
`Employee`, does a second query check whatever custom roles they've been
assigned - always resolved fresh from the database (`EmployeeCustomRoleRepository.findPermissionCodesForEmployeeUserId`),
never cached, since custom-role grants are edited at runtime and
`PermissionRegistry`'s cache has no invalidation story (see its own
Javadoc, written back in Phase 2, anticipating exactly this). A grant or
revocation takes effect on the assignee's *very next request* - no token
refresh needed, unlike a base-role or company change, which only self-heal
on refresh (see Phase 7's JWT-staleness note).

**The one hard guard: platform-only permissions can never be granted
through a custom role.** `COMPANY_CREATE`/`UPDATE`/`DELETE` and the new
`AUDIT_MANAGE` are rejected outright by `CustomRoleService.setPermissions`
- without this, a company `ADMIN` could hand themselves the ability to
delete *any* company on the platform, or purge the very audit trail meant
to hold them accountable, through a mechanism meant only to compose
company-scoped capabilities.

**Schema:** three new tables (`custom_role`, `custom_role_permission`,
`employee_custom_role`), all brand new - no migration script needed the
way Phase 6's composite-constraint change did, since `ddl-auto=update`
adds new tables just fine on any database, fresh or existing.

**Proof:** `CustomRoleHttpTest` - a plain `EMPLOYEE` who cannot call a
`REPORT_READ`-gated endpoint gains that ability purely through a
custom-role assignment (no change to their JWT or base role at any point),
and loses it again on unassignment; a platform-only code is rejected;
cross-company role management 404s exactly like every other cross-company
attempt in this app; HR is forbidden from managing roles.

## Bulk employee onboarding: structure override, salary revision history, credentials export (Phase 11)

Three related additions to how a company brings employees into the system
and keeps their pay current, all sharing one theme: don't silently guess or
silently lose data when the caller already has ground truth.

### Salary structure can be supplied directly, not only derived

Every employee create (single or bulk CSV) previously accepted only
`grossSalary`/`pfBasic`/`medicalAllowance`/`otherAllowance` and always
derived `basicDA`/`hra`/`conveyanceAllowance`/`educationAllowance` from the
company's `SalaryRule`. A company migrating employees from an existing
payroll system already knows those four numbers precisely - re-deriving them
from percentages risks silently disagreeing with figures that are already
correct and already compliant. `EmployeeRequest` and `EmployeeCsvParser`
CSV rows now accept the same four fields; supplying all four sets them
verbatim and marks the employee `salaryStructureOverridden` (the same flag
`PUT .../salary-structure` has always used), supplying none derives exactly
as before, and supplying some-but-not-all is rejected - a structure that's
part typed, part rule-derived was never really "fixed." `grossSalaryWage`
itself remains impossible to send directly under any circumstance; it is
always the server-computed sum, same as before this phase.

**Proof:** `EmployeeSalaryStructureHttpTest.createWithFullStructureOverride`,
`.createWithPartialStructureOverrideRejected`,
`.createWithNoStructureOverrideDerivesAsUsual`,
`.bulkImportWithStructureOverrideColumns` (all-override row, all-derive row,
and a partial-override row failing independently in the same batch),
`EmployeeCsvParserTest` (pure parsing, no HTTP).

### Salary revision history

A gross-salary change previously went through the ordinary `PUT
/api/employees/{id}`, indistinguishable from any other field edit - no
record of the old figure, when it changed, or why. `SalaryRevision`
(table `salary_revision`) is a new append-only audit table, written by
`POST /api/employees/{id}/salary-revision`: `previousGrossSalary`,
`newGrossSalary`, computed `hikePercent`, `effectiveDate`, `reason`, and
`revisedBy` (the caller's username, same actor convention `AuditLog` uses).
Like `AuditLog`, it stores `employeeId` as a plain scalar rather than a JPA
relation - deliberately, for the same reason: a history row is a snapshot of
what happened, not a live view of current employee state, and a
`@ManyToOne` here would reintroduce the exact lazy-serialization risk fixed
on `SalaryRule`/`Holiday` (see Phase 6). `GET
/api/employees/{id}/salary-revisions` is scoped by the same self-service
visibility rule (`EMPLOYEE_READ` + self/supervisor/HR/ADMIN) as every other
per-employee read in this app.

One interaction worth calling out: an employee with `salaryStructureOverridden
= true` does not have their structure move just because `grossSalary` does
(derivation is skipped entirely while overridden - see "Salary structure"
in [ARCHITECTURE.md](ARCHITECTURE.md)). `reviseSalary` therefore *requires*
the four replacement structure values in the same request whenever the
employee is currently overridden, rather than silently applying a new gross
salary against a now-stale structure.

**Proof:** `EmployeeSalaryRevisionHttpTest.hikeUpdatesGrossAndRederivesStructure`,
`.hikeOnOverriddenEmployeeWithoutStructureRejected` (400, nothing changes),
`.hikeOnOverriddenEmployeeWithStructureApplies`.

### Bulk-import credentials export - an accepted tradeoff, not a fix

`POST /api/employees/bulk-import` already returned every created employee's
one-time `temporaryPassword` inline in its JSON response (Phase 9). That
does not scale past a handful of rows - there is still no email/SMS delivery
infrastructure in this app (same gap Phase 9's admin-triggered reset works
around), so for a real batch of 50-500 people there was no practical way to
get password *N* to person *N* without HR manually matching rows in a JSON
array by hand.

`?format=csv` on the same endpoint returns a downloadable
`userId,employeeCode,employeeName,temporaryPassword` sheet
(`EmployeeCredentialsCsvWriter`) built from the exact same in-memory result
the JSON response would have used - no second lookup, nothing persisted,
same one-time-return contract as every other password path in this app.

**This is explicitly not a full fix, and the tradeoff was a deliberate,
discussed choice, not an oversight:** the file contains raw, immediately
usable passwords rather than one-time activation links, and there is still
no `mustChangePassword` enforcement anywhere (Phase 9's "should change it on
first login" remains a documented convention, not a code gate - see "Not
yet built" below). A downloaded credentials file sitting in a Downloads
folder is a real exposure surface. Real email/SMS delivery, activation
links instead of raw passwords, and enforced first-login password change
are the three natural follow-ups, in roughly that order of value, once this
app is ready to invest in delivery infrastructure it currently has none of.

**Proof:** `EmployeeSalaryStructureHttpTest.bulkImportCsvFormatReturnsCredentialsSheet`.

## Per-population attendance policy (Phase 12)

Two new permission codes, `ATTENDANCE_POLICY_READ` and
`ATTENDANCE_POLICY_MANAGE`, granted to HR and ADMIN alongside
`ATTENDANCE_RULE_*` and to nobody else.

**Deliberately separate from `ATTENDANCE_RULE_*`** rather than folded into it.
Those three thresholds apply company-wide and are visible in one screen; a
policy rule can dock a named category half a day each and is a strictly larger
blast radius. Granting the smaller should not silently grant the larger.

### Tenancy

`AttendancePolicyRule` carries a nullable `company`, the same shared-catalog
shape `Shift`, `Category`, `Department` and `Designation` have used since
Phase 6:

- **Read**: a company sees its own rules plus the shared `company = null` rows.
- **Write**: only its own. A company can never create, edit or delete a shared
  row, and a platform caller writes only shared rows.
- **Cross-company access returns 404, not 403** - the same shape an unknown id
  returns, so the response never confirms another company's rule exists. Same
  reasoning as every other tenant check since Phase 3.
- `GET /effective?userId=` resolves the target through
  `EmployeeService.getEntityByUserId`, the same choke point every cross-company
  check uses, so naming another company's employee 404s exactly as an unknown
  one would.

Unlike `Shift`, the shared catalog here is **seeded empty**. A seeded global
rule would change what every existing tenant is paid on the deploy that
introduced it.

### Two guards that are about money, not access control

- **Back-dating into a paid month is refused.** A rule version whose
  `effectiveFrom` reaches back to a locked attendance day is rejected with the
  months and employee count named. This is narrower than refusing recompute
  generally, and it is sufficient: because versions resolve by attendance date,
  a forward-dated rule provably cannot change a past month. Without it, merely
  *running a report* would re-price a locked month, since `ReportService` calls
  `syncSummaries`, which persists.
- **A rule that has ever been in force cannot be deleted**, only superseded by a
  disabled version - a day it priced may already be on a payslip, and the trace
  rows explaining that day point at its id. Only a version whose `effectiveFrom`
  is still in the future is deletable, because it has priced nothing.

### Input handling

Rule parameters arrive as JSON and are bound to a per-`RuleType` record and
bean-validated before anything is written - never read as a loose map, and
never evaluated. `FAIL_ON_UNKNOWN_PROPERTIES` is on, so a misspelt field is a
400 naming it rather than a rule silently running on a zero grace. A stored blob
that will not bind fails generation by name rather than being skipped: silently
dropping a rule would change pay by omission.

Every mutation is audited through `AuditService`
(`ATTENDANCE_POLICY_RULE_CREATE`, `ATTENDANCE_POLICY_RULE_DELETE`) with the
rule label, effective date and full parameters in the detail.

**Proof:** `AttendancePolicyHttpTest` - 15 tests covering the employee-token
refusal, cross-company read and delete, the back-dating guard, the
delete-only-if-future rule, and every validation message above.

## Configurable employment types (Phase 13)

Two new permission codes, `EMPLOYMENT_TYPE_READ` and `EMPLOYMENT_TYPE_MANAGE`,
granted to **HR and ADMIN only**.

Deliberately *not* granted to SUPERVISOR or EMPLOYEE, unlike the master-data
reads (`CATEGORY_READ`, `DEPARTMENT_READ`, `DESIGNATION_READ`) it sits next to
in the catalog. Those are labels; an employment type is the rule deciding
whether somebody is paid per attended day or per calendar day, and how their
overtime is computed. It belongs with `SALARY_RULE_READ`.

### Tenancy

`EmploymentType` carries a nullable `company` — the Phase 6 shared-catalog
shape. A company reads its own rows plus the shared ones, writes only its own,
and can never edit a shared row: an in-place edit to a shared type's pay basis
would silently change what every company using it pays, the same class of gap
Phase 6 closed for `Shift`. Cross-company access returns 404, not 403.

`EmployeeService` resolves `employmentTypeId` through
`EmploymentTypeService.getById`, so an employee can never be assigned another
company's private type — the same choke-point inheritance
department/designation/category already rely on, with no change to the
assignment code itself.

### Guards specific to money

- **A `PER_ATTENDED_DAY` type may not also apply LOP.** Attendance already
  decides what such an employee is paid, so loss of pay would deduct the same
  absence a second time. Refused on write.
- **A type in use cannot be deleted**, only deactivated. It is referenced by
  employee rows and, through the payroll snapshot, by every payslip computed
  under it.
- **`Payroll` snapshots the pay basis it was computed with.** Without it, a
  company editing a type would change how already-paid periods are laid out and
  reconciled in the registers, breaking the immutable-payroll-history guarantee
  even though no money moved.

### The safety property

`Employee.employmentType` is nullable and `Employee.status` is untouched. An
employee with no type falls back to the legacy `EmployeeStatus` semantics
exactly, so deploying this to a running client changes nothing until somebody
deliberately assigns a type. Every mutation is audited
(`EMPLOYMENT_TYPE_CREATE` / `_UPDATE` / `_DELETE` / `_SEED`) with the full
behaviour in the detail.

**Proof:** `EmploymentTypeHttpTest` (8 tests) and
`EmploymentTypePayrollTest.noEmploymentTypeFallsBackToTheLegacyEnum`.

## Reports scoped to a supervisor's team, and two silent state changes (Phase 14)

### Reports and the dashboard follow self-service scoping

Phase 8 left `REPORT_READ` and `DASHBOARD_READ` company-wide, and every
`SUPERVISOR` holds both. The UI hid the Reports menu from supervisors, but the
API did not: any supervisor could pull the whole company's payroll register,
bank advice, PF ECR, ESI return, PT register and TDS figures. The product
decision is now that a supervisor sees their own team's reports only.

- **One population.** `EmployeeService.getVisibleEntities()` /
  `getActiveVisibleEntities()` are the company's employees narrowed by the same
  rule as `assertSelfOrManages` - HR/ADMIN everyone, a SUPERVISOR themselves
  plus direct reports, an EMPLOYEE themselves - checked against the loaded
  entities rather than one lookup per row. `ReportScope`, `ReportService`,
  `ReportController.employeeReport` and `DashboardService` all read from it, so
  totals (by department, the audit summary, bank advice control totals) are
  computed over the team, not filtered after the fact.
- **One way to read a period.** The unrestricted `PayrollService.getPeriod` is
  gone; `getPeriodForCaller` is the only period read, used by every report, the
  dashboard, `PayrollController` and the salary slip exports.
- **The dashboard opened for supervisors again.** `DashboardService` used to
  load the whole company and then `assertSelfOrManages` every employee, which
  threw a 404 for any supervisor in a company with anyone outside their team.
- Deactivated employees stay in HR's payroll reports, as before.

**Proof:** `SupervisorReportScopingHttpTest` (6 tests).

### Custom role permissions: saving an overlapping list returned 409

`CustomRoleService.setPermissions` deleted every grant and inserted the new
list. Hibernate inserts an IDENTITY row as soon as it is saved but holds
deletes until flush, so re-inserting a permission the role already had hit
`uk_custom_role_permission` - ticking every box, or saving an unchanged list,
failed with "Request violates a database constraint". It now removes only the
unticked grants and inserts only the new ones.

**Proof:** `CustomRoleHttpTest.savingAnOverlappingPermissionListReplacesItExactly`.

### An update that omitted a field reset it

`EmployeeService.apply` defaulted `role` to EMPLOYEE and `recordStatus` to
ACTIVE on update as well as create, so a `PUT /api/employees/{id}` without them
silently demoted a supervisor (hiding their team) or reactivated a deactivated
employee. Omitted now keeps the current value; the defaults apply only to a new
record.

**Proof:** `EmployeeUpdateHttpTest` (4 tests).

## Custom roles that work end to end (Phase 15)

Phase 10 made custom roles grant permissions, and `@authz.can` honoured them -
but nothing else did. The UI decided what to show from a hardcoded role ->
permission table, and several services, having let the request past the gate,
then asked for the HR or ADMIN role by name. An employee given a custom role saw
no new menu, and a supervisor given `LEAVE_APPROVE` was told that "final leave
approval requires the HR or ADMIN role".

### One answer, asked three ways

`AuthorizationService` now answers the same question for the gate
(`can`), for a login (`effectivePermissions`) and for a service-layer check
about an actor who is not the caller (`employeeCan`) - base role grants from
`PermissionRegistry` plus, for an employee, their custom roles, read fresh.

- **Login and refresh return `permissions`.** `TokenResponse` carries the
  session's effective permission codes; the SPA's `can()` reads them instead of
  its own copy of the grant table, and the sidebar and route guards share one
  set of rules (`constants/access.js`). A permission list from an older server
  is absent rather than wrong, so the UI falls back to the fixed role's grants.
- **Capability checks ask for the permission.** `AttendanceService` (generate /
  correct / unlock) and `LeaveService` (final approval, HR-direct entry) no
  longer test for HR or ADMIN by name.

### Whose records: `EmployeeService.assertManages`

A permission alone must not decide *whose* attendance may be corrected or whose
leave approved, so every one of those writes now resolves its target through
`assertManages`: HR/ADMIN the whole company, a SUPERVISOR their direct reports,
anyone else nobody - and, unlike `assertSelfOrManages`, **not the caller's own
record** for a team-scoped caller. A custom role handing a supervisor
`ATTENDANCE_CORRECT` or `LEAVE_APPROVE` must not let them correct their own
attendance or approve, reject or cancel their own leave. ADMIN/HR, who could
always do both, are unchanged. A generation run with no `userIds` covers the
managed population rather than the whole company.

Scope is deliberately still the fixed role's to decide; a custom role cannot
widen it (see "Not yet built").

**Proof:** `CustomRoleCapabilityHttpTest` (8 tests),
`CustomRoleHttpTest.loginPermissionsIncludeCustomRoles`,
`AuthApiHttpTest.loginAndRefreshReturnEffectivePermissions`, and on the frontend
`navConfig.test.js`, `access.test.js` and `AuthContext.test.jsx`.

## Data scope as a grantable permission (Phase 16)

Phase 15 left reach with the fixed role, which a director's organisation does
not fit: their direct reports are team leads, and the people doing the work are
a level further down, invisible to them. The answer is not a second kind of
role but three more permissions.

### `DataScope`

`SCOPE_DIRECT_REPORTS`, `SCOPE_ALL_REPORTS`, `SCOPE_COMPANY` - ordered, each
including the one before. `PermissionSeeder` grants them to the fixed roles
exactly as those roles always behaved (COMPANY to ADMIN/HR, DIRECT_REPORTS to
SUPERVISOR, none to EMPLOYEE), so nothing changes until somebody grants more,
and a custom role carries anything wider. The role's own scope stays a floor
under the granted one, so a database whose `role_permission` rows predate these
codes still shows HR the company rather than nothing.

`EmployeeService.scopeOf` resolves it - from the caller's effective permissions,
or from an actor entity for the service-layer checks that take an actor id - and
every visibility decision already funnelled through `assertManages`,
`assertSelfOrManages` and `getVisibleEntities`, so the directory, reports, the
dashboard, the roster planner, leave decisions and attendance corrections all
follow at once. `ALL_REPORTS` is answered by walking **up** from the target to
see whether the caller sits above it: one lookup per level, no recursive query,
and cycles (already refused when a supervisor is assigned) cannot loop it.

Two role checks that predated this are gone with it:
`ShiftSchedulingService.assertMaySchedule` and `LeaveService.assertSupervisorOf`
now ask whether the actor manages the target, so a director can roster and
endorse two levels down.

**Scope is reach, not power.** `SCOPE_COMPANY` lets a custom-role holder *see*
the company; deciding leave still needs `LEAVE_APPROVE`, and correcting
attendance still needs `ATTENDANCE_CORRECT`. And a team-scoped caller still
never acts on their own record (Phase 15).

**Proof:** `DirectorScopeHttpTest` (6 tests), plus the frontend's
`access.test.js` and `navConfig.test.js`, where the Team screens now ask for a
scope rather than a role name.

## The admin is a company account, and edits are the admin's (Phase 17)

### The ADMIN login is not an employee

Company onboarding creates the first `ADMIN` as a login, not a member of staff:
no salary, employee code or joining date is asked for or stored
(`CompanyOnboardingRequest` no longer carries them). `Employee.isCompanyAccount()`
(`role == ADMIN`) is what every process reads, and it lives in the same two choke
points the contractor split uses:

- `EmployeeService.getActiveEntities()` / `getAllEntities()` leave it out, so
  payroll runs, attendance generation, the dashboard, every report, the roster
  planner and the employee directory never see it - no headcount, no payslip, no
  attendance.
- It is refused as the *subject* of an employee process: leave applied for or
  entered (`LeaveService.apply`, `assertManages` for HR-direct entry and every
  decision), attendance generated or corrected (`assertManages`), payroll by name
  (`PayrollService.build`) and leave quotas. Leave balances read as empty rather
  than showing the fallback 12 casual / 8 sick days.
- It is still an actor, with the whole company as its scope: it creates and edits
  staff, approves leave, runs payroll. Only its own workspace is gone - the UI
  drops "My Workspace" for it (`ACCESS.workspace`).

### A company has one admin

The account onboarding creates is the only `ADMIN`. `EmployeeService.apply` refuses
to give the role to anyone (create, update, bulk import) and refuses to take it away
from the admin, and `deactivate` refuses the admin - either would leave the company
with nobody, or a second person, in charge. It is a 400 whoever asks. The UI's role
dropdown and CSV template no longer offer ADMIN.

### Whose record may be changed

`EmployeeService.assertMayChange` runs on every write to an employee record - edit,
salary revision, structure override/regenerate, password reset, unlock,
deactivate, supervisor reassignment. An `ADMIN` may change anyone's. Everyone else
who holds `EMPLOYEE_UPDATE` - HR, or anyone given it through a custom role - may
change everyone's **but their own and the admin's** (403). Your own pay, role and
account are for somebody else to decide, and the admin is the one account HR must
not be able to reset, demote or deactivate.

The rule is about the record, not the role name, so it holds for a custom role
exactly as for HR.

### Nobody runs attendance, leave or payroll on their own record

The same principle for the processes themselves. `EmployeeService.manages` - the
one "may act on this person" check behind attendance generation, correction and
unlock, and every leave decision and HR-direct entry - no longer treats the
caller's own row as theirs, even with company scope; HR's own leave, attendance
and pay are for the admin (or, for endorsement and rostering, their own supervisor).
Two processes are not gated by `manages`, so they get `assertNotSelf`: payroll
(`PayrollService.build`, so generate, regenerate and bulk runs alike) and leave
quotas (`LeaveBalanceService.setQuota`). A whole-company payroll run by HR simply
leaves HR's own row out of the run (`getActiveEntitiesExceptCaller`), for the admin's
run to pay - and a whole-company attendance generation by HR likewise skips HR's own
row, so month-end needs the admin's run to cover them. Endorsing your own leave is refused in `LeaveService.assertSupervisorOf`.
Applying for your own leave is unchanged - the deciding is what moves.

Rostering (`ShiftSchedulingService`) still goes through `managesEmployee`, which is
unchanged: HR may still roster their own shifts.

**Proof:** `CompanyAccountHttpTest` (18 tests), `CompanyOnboardingHttpTest.onboardingCreatesAdminWithNoPay`, and on
the frontend `access.test.js`, `navConfig.test.js`, `enums.test.js`, `LeaveLayout.test.js`,
`OnboardCompany.test.jsx`, `EmployeeDetail.test.jsx`, `EmployeeList.test.jsx`,
`PendingApprovals.test.jsx`.

## Platform account names are reserved (Phase 18)

### The problem

Employees and platform users share one login form, and `AuthService.dispatchLogin`
looks in the **employee table first**: only if no employee has that userId does it try
the platform table. Nothing stopped an employee being created with a platform
account's name - so a company admin (or HR, or a custom role holding `EMPLOYEE_CREATE`,
or a CSV import) could add an employee called `platform_owner`. That employee gained
nothing: their session is an employee session with their company role, and the
platform-only permissions (`COMPANY_CREATE` and the rest) cannot be granted to a
company user at all. But the platform owner's own login now landed on the employee row,
was checked against that employee's password, and failed - a company could lock the
platform owner out of the platform. (Found live in the 2026-09-19 review.)

### The fix

A platform username is unavailable as an employee userId, on every route that gives an
employee row its userId:

| Route | Where |
|---|---|
| Create an employee, and CSV bulk import (which calls it per row) | `EmployeeService.create` |
| Rename an employee | `EmployeeService.update` |
| Create or rename a contractor's worker | `ContractorEmployeeService.create` / `update` - a worker has no login, but the row would still answer the name |
| Onboard a company whose admin has that name | `CompanyOnboardingService.onboard` |

The check is `PlatformUserRepository.existsByUsernameIgnoreCase`, so it holds however
the name is capitalised - and, because the comparison runs in the database like the
login lookup does, on MySQL's default collation for accent variants too.

The refusal is a 409 carrying **exactly the message a plain duplicate userId gets**
("Employee already exists with userId ..."), so it does not tell a caller that the
name belongs to a platform account. Platform accounts are only ever created by the
seeder, never through the API, so there is no route in the other direction to close.

### Databases that already have a clash

The fix stops new clashes; it does not repair an old one. To check:

```sql
select user_id, company_id from employee
where lower(user_id) in (select lower(username) from platform_user);
```

No rows means nothing to do. A row means that company's employee is answering the
platform owner's login: have the company rename that employee (the edit is now
permitted, and refuses only names that are still reserved), or rename the row in the
database. Until then the platform owner cannot sign in.

Login precedence itself is unchanged (employees are still looked up first); with the
names reserved it can no longer matter for new data.

**Proof:** `ReservedUserIdHttpTest` (4 tests) - each route above, in two letter cases,
the identical-message check, and that the platform owner can still sign in afterwards.

## The mobile client (Phase 19)

The Muster app (`AccusharpMobile/`) is a native client of this same API for
employees and supervisors. It introduces no endpoint and no privilege; what it
changes is who is on the other end of the login screen, which is worth writing
down.

### How it holds the session

- The **access token** (15 minutes) lives in memory only. The **refresh token**
  (7 days) is read from the httpOnly `Set-Cookie` header by the app itself - a
  phone has no cookie jar it can rely on, and iOS drops a `Secure` cookie that
  arrived over plain http - kept in the Keychain / Android Keystore
  (`expo-secure-store`), and replayed as a `Cookie` header on `/auth/refresh` and
  `/auth/logout` only, which is the narrowing `Path=/api/auth` gives a browser. It
  is never sent to a business endpoint.
- Refresh rotation is **single-use**, and a replayed token ends the session. The
  app therefore shares one refresh between concurrent requests. The converse is
  an accepted cost: if a refresh succeeds on the server but the response never
  reaches a phone (a dead spot), the retry replays the old token and the person
  signs in again.
- `permissions` in the login/refresh body decides what the app shows, and the same
  list is what the API enforces. A role or custom-role change therefore reaches a
  phone within one access-token lifetime.
- **The company ADMIN is signed in as an `EMPLOYEE` principal holding every
  permission** (Phase 17 made it a company account, not an employee). A client
  must not infer a workspace from permissions: the mobile app and the web app both
  key on the role, and send the admin to the web app / no workspace.

### What was checked, live

A script (`AccusharpMobile/scripts/contract-smoke.mjs`, `npm run contract`) signs in
as an admin, HR, a supervisor, an employee and a director on a scratch backend and
asserts: the cookie's shape and rotation, replay refused, logout clearing the
cookie, a colleague's attendance 404, a supervisor's team list carrying no pay or
bank fields, a supervisor refused a team member's slip (403), supervisor reject and
employee cancel refused (403), the admin refused leave (400), and the lock after
five wrong passwords. It creates a company, so it refuses a non-local address unless
told otherwise.

### Things to know when running it for a workforce

- **The login throttle is per source address** (`security.login-rate-limit.*`,
  15 failures in 5 minutes by default, cleared by a successful login from that
  address). A factory on one Wi-Fi shares one address, and people who read slowly
  mistype: fifteen wrong attempts across the whole floor would hold everyone off for
  a few minutes. Raise `max-failures` for such a site, or leave it - the per-account
  lock (five tries) is the control that matters. Behind a proxy, `APP_TRUSTED_PROXIES`
  must name it, or every phone shares the proxy's address (`deploy/docker-compose.yml`
  does).
- **A locked account is cleared by HR/ADMIN only** (password reset). The app says so
  in the person's language; there is no self-service recovery (see below).
- **The server address is baked into a release build** (`EXPO_PUBLIC_API_URL`);
  release builds block plain http. A build made without one has no server and says so
  rather than calling localhost.
- **`includeUsual` and `approvalFlow` widen nothing**: the roster read keeps its
  self-or-manages scope (a colleague's roster is a 404 with or without the flag), and
  the approval flow of a request is only ever returned to those who can already read
  the request.

## A user id typed in another case is still the same person (Phase 20)

### The problem

Phase 17 stopped HR (and anyone holding company-wide scope through a custom role)
acting on their own record: approving their own leave, entering leave for themselves,
setting their own leave quota, generating, correcting or unlocking their own
attendance. The guards decided "is the target the caller?" by comparing the caller's
stored id with the id in the request using a case-sensitive `equals`.

The production database is MySQL, and its default collation (`utf8mb4_0900_ai_ci`)
compares user ids without regard to case - and accents. So `cvhr` finds the row
`CVHR`: every read and write resolves to HR's own record, while the guard saw a
different string and treated it as somebody else. HR signed in as `CVHR` could apply
for leave as `cvhr`, approve it, raise their own quota and rewrite their own
attendance. The tests could not see it, because H2 compares case-sensitively. (Found
in the 2026-10-03 security review; the owner's rule was incomplete, no new capability
was added - before Phase 17 HR could do all of this with the exact id.)

### The fix

- `EmployeeService.storedUserId(typed)` returns the id as it is stored. Every guard that
  compares ids now compares stored forms: `assertNotSelf`, `assertManages`,
  `assertSelfOrManages` (so also the leave, attendance and quota paths that call them)
  and `LeaveService.assertSupervisorOf`. The supervisor and director scopes already
  compared stored ids on both sides.
- `LeaveService.apply`, `hrDirectCreate` and `LeaveBalanceService.setQuota` write the
  person's stored id, so a typed `cvemp` produces a request and a balance for `CVEMP`,
  not a second row. Requests written before this keep whatever case was typed; the
  guards handle them too (`aLegacyRowStoredUnderTheTypedIdIsStillGuarded`).
- `CaseVariantUserIdHttpTest` (10 tests) runs on an H2 database created with
  `IGNORECASE=TRUE`, which behaves like MySQL here, and checks the exact same requests
  with a lower-case id: HR is refused on their own record and still works for everyone
  else however their id is typed.

### Known and left alone

Rules that name one person (`EMPLOYEE`-scope work policies and attendance policy rules)
match `scopeRef` against the stored id with a case-sensitive `equals`, so a rule saved
as `dir001` does not apply to `DIR001`. That is a correctness gap, not an access one:
it can only make a rule not apply. Roster self-assignment is unchanged, as before.

## Not yet built (next phases)

- Platform-owner company onboarding flow beyond raw CRUD.
- **Self-service** account unlock - the admin half exists (Phase 9's
  password reset also clears lockout), but there's no way for a locked-out
  employee to recover *without* an ADMIN/HR acting on their behalf, and no
  standalone "just unlock, don't also reset the password" endpoint.
- Forgot-password **email** flow specifically - Phase 9's admin-triggered
  reset is the practical stand-in this project has instead, and remains the
  only recovery path; a self-service, no-admin-involved flow still needs
  email delivery infrastructure this app does not have. Phase 11's bulk
  credentials CSV export is a distribution workaround on top of the same
  missing infrastructure, not a replacement for it.
- A `mustChangePassword` enforcement gate - today an employee (single-created,
  bulk-imported, or password-reset) can use their temporary password
  indefinitely; "change it on first login" is a documented convention
  (Phase 9, Phase 11), never a server-side check.
- One-time activation links instead of raw temporary passwords - would
  remove plaintext credentials from both the API response and the Phase 11
  bulk credentials file entirely, at the cost of needing a delivery channel
  (email/SMS) to actually get the link to each employee, which this app
  does not have.
- A decision on whether a custom role should be able to grant *more* than
  what its own creator (ADMIN) already holds, which today it can (only the
  platform-only codes are blocked, not a general no-privilege-escalation
  check).
- A scope narrower than a whole subtree - "this department", "this site" -
  which `DataScope` (Phase 16) has no value for; today the choices are the
  caller alone, their direct reports, everyone below them, or the company.

See the original security analysis in this repository's PR/session history
for the full phased plan.
