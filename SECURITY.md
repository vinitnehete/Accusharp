# Security - what exists today, and what is next

This covers **Phase 1** (authentication: login, password hashing, JWT),
**Phase 2** (authorization: permissions, `@PreAuthorize` on every business
endpoint), **Phase 3** (multi-tenant isolation: a company cannot reach
another company's data by id), **Phase 4** (platform company onboarding),
and **Phase 5** (audit logging) of a multi-phase security rollout. Read this
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

## Not yet built (next phases)

See also [SECURITY_AUDIT.md](SECURITY_AUDIT.md#open-findings-not-fixed) for
the two items there with an assigned severity and a recommended interim
mitigation (`Department`/`Designation`/`Shift` tenant isolation, and
list/report endpoint scoping) - both restated below for completeness.

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
- `Department`/`Designation`/`Shift` are still global masters shared by every
  company - unlike `SalaryRule`, these were **not** migrated to per-company
  rows in Phase 3. Their unique constraints are single-column
  (`department_code`, `designation_code`, `shift_code`); making them
  per-company means a composite `(company_id, code)` constraint, and this
  project has no migration tool (`ddl-auto=update` only - see README §13) to
  safely alter an existing unique index on a live database. Doing this
  without one means either a manual `ALTER TABLE` step documented for
  operators, or waiting for Flyway/Liquibase to land first.
- "View only my own data" self-service scoping - today any authenticated
  principal holding a `_READ` permission can read *any* employee's records by
  id/userId, not only their own (the choke point stops *cross-company*
  reads, not *cross-employee-within-a-company* reads).
- List/report endpoints still return cross-company data where no natural
  choke point caught them: `EmployeeService.getAll()`, every
  `ReportService.*` method, `DashboardService`, `PayrollService.getPeriod`.
  (`LeaveService`'s list methods are the exception - see above.)
- Platform-owner company onboarding flow beyond raw CRUD.
- Audit logging.
- Self-service / admin account unlock.
- Forgot-password email flow (needs email delivery infrastructure this app
  does not have yet).

See the original security analysis in this repository's PR/session history
for the full phased plan.
