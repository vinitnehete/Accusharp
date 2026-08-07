# Security - what exists today, and what is next

This covers **Phase 1** (authentication: login, password hashing, JWT) and
**Phase 2** (authorization: permissions, `@PreAuthorize` on every business
endpoint) of a multi-phase security rollout. Read this alongside
[README.md](README.md) §13 and [ARCHITECTURE.md](ARCHITECTURE.md) "Roles".

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

A missing/invalid token → `401` (`RestAuthenticationEntryPoint`). An
authenticated principal without the required permission → `403`
(`RestAccessDeniedHandler` at the filter-chain level, or
`GlobalExceptionHandler`'s `AccessDeniedException` handler for
`@PreAuthorize` denials) - both produce the identical `ApiError` shape.

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

## Not yet built (next phases)

- Dynamic role/permission management endpoints (create a custom role, assign
  permissions to it, assign it to a user) - today's grants are fixed at
  startup by `PermissionSeeder`.
- Multi-tenant company isolation (`company_id` on the currently-global
  `Department`/`Designation`/`Shift`/`SalaryRule`, tenant checks on every
  cross-company lookup, IDOR fixes on every `findById`).
- "View only my own data" self-service scoping - today any authenticated
  principal holding a `_READ` permission can read *any* employee's records by
  id/userId, not only their own. Closing this is tangled up with tenant
  isolation above (both are "does this caller actually own this resource"
  checks) and is deferred to the same phase.
- Platform-owner company onboarding flow beyond raw CRUD.
- Audit logging.
- Self-service / admin account unlock.
- Forgot-password email flow (needs email delivery infrastructure this app
  does not have yet).

See the original security analysis in this repository's PR/session history
for the full phased plan.
