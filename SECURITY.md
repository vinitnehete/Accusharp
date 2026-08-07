# Security - what exists today, and what is next

This is **Phase 1** of a multi-phase security rollout. It adds real
authentication (login, password hashing, JWT). It deliberately does **not**
yet gate any business endpoint on that authentication - see
[Scope of this phase](#scope-of-this-phase) below. Read this alongside
[README.md](README.md) §13 and [ARCHITECTURE.md](ARCHITECTURE.md) "Roles".

## What exists

### Two principal types

- **`Employee`** - a company user. `userId` (e.g. `EMP001`, `HR001`) is the
  login username. Carries `passwordHash`, `accountEnabled`, `accountLocked`,
  `failedLoginAttempts`, `lastLoginAt`.
- **`PlatformUser`** - a platform-level account (company onboarding etc.), not
  tied to any company. Kept as its own entity rather than a "companyless"
  Employee, since Employee carries payroll/attendance fields that make no
  sense for platform staff.

### Login, refresh, logout, change-password

```
POST /api/auth/login            { "username": "EMP001", "password": "..." }
POST /api/auth/refresh          { "refreshToken": "..." }
POST /api/auth/logout           { "refreshToken": "..." }
POST /api/auth/change-password  { "currentPassword": "...", "newPassword": "..." }   (needs a bearer token)
```

`login` returns:

```json
{
  "accessToken": "...", "refreshToken": "...", "tokenType": "Bearer",
  "expiresInSeconds": 900, "principalType": "EMPLOYEE", "username": "EMP001", "role": "EMPLOYEE"
}
```

- **Access tokens** are short-lived JWTs (15 min default), signed HS256,
  self-contained (subject, principal type, companyId, role) - validated
  without a database round trip on every request.
- **Refresh tokens** are opaque random values, **not** JWTs. Only a SHA-256
  hash is stored (`refresh_token` table), so a database read alone cannot be
  replayed as a credential. Every refresh **rotates**: the old token is
  revoked, a new one issued. A revoked token cannot be replayed - test it with
  `AuthApiHttpTest.refreshRotatesAndOldTokenIsRejected`.
- **Account lockout**: 5 failed attempts (`security.max-failed-login-attempts`)
  locks the account. There is no self-service unlock yet - that is an
  admin action that arrives with Phase 2's `@PreAuthorize` work.
- **No account enumeration**: an unknown username and a wrong password return
  the identical `401 Invalid username or password`. Locked/disabled status
  *is* reported distinctly - that's considered useful to a real user, not a
  credential leak.
- **Password change revokes all of that principal's refresh tokens** - a
  password change ends every other logged-in session.

### Where things are

```
security/            JwtService, RefreshTokenService, JwtAuthenticationFilter,
                      UserPrincipal, CustomUserDetailsService,
                      RestAuthenticationEntryPoint, RestAccessDeniedHandler
config/SecurityConfig Filter chain, CORS, password encoder
service/AuthService   Login/refresh/logout/change-password business logic
controller/AuthController
entity/{Employee,PlatformUser,RefreshToken}
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

## Scope of this phase

Business endpoints under `/api/**` (everything except `/api/auth/**`) are
still `permitAll` in `SecurityConfig` - marked with a `TODO(Phase 2)`. This
phase's job was to build a real, tested authentication path (hashing, tokens,
lockout, revocation) without touching the business-endpoint behavior every
existing test and Postman flow already depends on. **A caller can still claim
to be `HR001` on `/api/leaves/{id}/approve` today** - the same gap
`README.md` §13 has always documented, just narrowed to "endpoint
authorization is missing" rather than "authentication is missing entirely."

## Not yet built (next phases)

- `@PreAuthorize` / deny-by-default on all 17 business controllers.
- A dynamic Role/Permission/RolePermission model (today's `Role` enum stays
  as-is in this phase).
- Multi-tenant company isolation (`company_id` on the currently-global
  `Department`/`Designation`/`Shift`/`SalaryRule`, tenant checks on every
  cross-company lookup).
- Platform-owner company onboarding endpoints.
- Audit logging.
- Self-service / admin account unlock.
- Forgot-password email flow (needs email delivery infrastructure this app
  does not have yet).

See the original security analysis in this repository's PR/session history
for the full phased plan (Phases 1-5).
