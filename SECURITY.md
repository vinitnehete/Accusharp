# Security - what exists today, and what is next

This covers **Phase 1** (authentication: login, password hashing, JWT),
**Phase 2** (authorization: permissions, `@PreAuthorize` on every business
endpoint), **Phase 3** (multi-tenant isolation: a company cannot reach
another company's data by id), **Phase 4** (platform company onboarding),
**Phase 5** (audit logging), **Phase 6** (per-company masters and
report/dashboard scoping), **Phase 7** (a full re-audit of every remaining
service, which found and fixed four more cross-company gaps), and **Phase
8** ("view only my own data" self-service scoping) of a multi-phase
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
| `DEPARTMENT_MANAGE`, `DESIGNATION_MANAGE`, `SHIFT_MANAGE`, `HOLIDAY_MANAGE` | ✓ | ✓ | - | - | - |
| `DEPARTMENT_READ`, `DESIGNATION_READ`, `SHIFT_READ`, `HOLIDAY_READ` | ✓ | ✓ | ✓ | ✓ | - |
| `EMPLOYEE_CREATE/UPDATE/DELETE` | ✓ | ✓ | - | - | - |
| `EMPLOYEE_READ` | ✓ | ✓ | ✓ | ✓ | - |
| `SHIFT_SCHEDULE_MANAGE` | ✓ | ✓ | ✓ (own team, enforced in service) | - | - |
| `SHIFT_SCHEDULE_READ`, `ATTENDANCE_READ` | ✓ | ✓ | ✓ | ✓ | - |
| `ATTENDANCE_GENERATE/CORRECT/UNLOCK` | ✓ | ✓ | - | - | - |
| `LEAVE_APPLY`, `LEAVE_READ`, `LEAVE_BALANCE_READ` | ✓ | ✓ | ✓ | ✓ | - |
| `LEAVE_SUPERVISOR_APPROVE` | ✓ | ✓ | ✓ (own team, enforced in service) | - | - |
| `LEAVE_APPROVE` (approve/reject/cancel) | ✓ | ✓ | - | - | - |
| `LEAVE_BALANCE_MANAGE`, `SALARY_RULE_READ/MANAGE`, `PAYROLL_PROCESS` | ✓ | ✓ | - | - | - |
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

Granting the `ADMIN` role is itself ADMIN-only: `EmployeeController` rejects
a create/update where `role: ADMIN` is requested by a caller who isn't
already ADMIN, even though HR otherwise has full `EMPLOYEE_CREATE`/`_UPDATE`
rights. Enforced in the controller (not `EmployeeService`) so the many
existing service-level tests that call `EmployeeService` directly - with no
`SecurityContext` populated - are unaffected.

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
  fix.

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

## Not yet built (next phases)

- Dynamic role/permission management endpoints (create a custom role, assign
  permissions to it, assign it to a user) - today's grants are fixed at
  startup by `PermissionSeeder`. Deliberately not attempted in Phase 4: it
  needs `Employee.role` to move from a plain enum column to a real
  relational assignment, which is a larger migration than this pass's
  budget allowed - see Phase 2's `RolePermission` Javadoc for the same point.
- Company activate/deactivate as dedicated endpoints - `PUT /api/companies/{id}`
  already accepts a `status` change, so this may already be sufficient;
  revisit only if a dedicated audit trail per status change is needed.
- Full audit coverage of every sensitive action listed in the original spec
  (see "Covered today" above for what Phase 5 actually shipped).
- Audit log retention/export tooling - today it is an unbounded table with
  no archival or deletion policy.
- Platform-owner company onboarding flow beyond raw CRUD.
- Audit logging.
- Self-service / admin account unlock.
- Forgot-password email flow (needs email delivery infrastructure this app
  does not have yet).

See the original security analysis in this repository's PR/session history
for the full phased plan.
