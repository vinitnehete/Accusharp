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
audit log retention/export), and **Phase 10** (dynamic role/permission
management) of a multi-phase security rollout. Read this
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
- A UI for assigning/removing an employee's custom roles and browsing
  what a role grants (Phase 10 shipped the API only, see
  `CustomRoleController`) - and a decision on whether a custom role should
  be able to grant *more* than what its own creator (ADMIN) already holds,
  which today it can (Phase 10 blocks only the platform-only codes, not a
  general no-privilege-escalation check).

See the original security analysis in this repository's PR/session history
for the full phased plan.
