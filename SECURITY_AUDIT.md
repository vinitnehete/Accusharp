# Security Audit — Accusharp HRMS

**Update (2026-08-08):** both findings this document left open below were
closed in Phase 6 - see [SECURITY.md](SECURITY.md#multi-tenant-masters-and-reports-phase-6).
The rest of this document is kept as-written, an accurate record of the
2026-08-07 audit pass.

**Date:** 2026-08-07 (Phases 1–5 of the security rollout, plus this audit pass)
**Scope:** Everything under `src/main/java/com/accusharp/hrms/security`, `config`,
`controller`, and every service/repository those controllers reach — i.e. the
full authentication, authorization, and multi-tenant isolation surface built
across Phases 1–5. Business calculation logic (payroll math, attendance
windowing) was in scope only where it intersects tenant boundaries, not for
its own correctness — that has its own extensive test suite and is out of
this audit's brief.

**Method:** Re-derivation, not re-reading of my own prior summaries. Every
claim below was checked against the current code or, where practical, proven
with a failing-then-passing test. Three findings (Holiday IDOR, Attendance
write-path IDOR, the `Role.ADMIN` over-grant) were caught this way and would
not have surfaced from re-reading SECURITY.md alone.

**Result:** 6 findings fixed in this pass (2 of them severe), 3 accepted
risks documented, 2 medium/high findings deliberately left open with a
recommended remediation path (both require product decisions or a migration
tool this project doesn't have — see [Open findings](#open-findings-not-fixed)).
All 73 tests pass, including 9 new ones written specifically to prove the
fixes below rather than just asserting they work.

---

## Findings fixed in this pass

### 1. HIGH — Holiday IDOR: any company could read, update, or delete any other company's holidays

**Where:** `HolidayService.getById` (and therefore `update`, `delete`),
`HolidayController.create`/`update` accepting a client-supplied `companyId`,
`getAll`/`getBetween` with no company filter at all.

**Impact:** `HolidayService.getById` was a raw `findById` with **no tenant
check whatsoever** — the one thing every other single-resource lookup in this
app has had since Phase 3. Any authenticated HR/ADMIN, from any company,
could:
- `PUT /api/holidays/{id}` to overwrite another company's holiday (change its
  date or flip it from mandatory to optional), or
- `DELETE /api/holidays/{id}` to remove a mandatory holiday from another
  company's calendar outright, or
- `POST /api/holidays` with `"companyId": <someone else's id>` to plant a
  fake holiday directly into another company's calendar.

Deleting or altering a mandatory holiday isn't cosmetic — the attendance
engine treats "not a working day" as authoritative for LOP calculation (see
`Attendance.md` invariant 3: "A weekly off or mandatory holiday can never
become loss of pay"). Removing one from another company's calendar would
turn a paid day off into an unpaid absence for everyone rostered that day,
silently, the next time attendance was generated.

**Why it was missed in Phase 3:** the Phase 3 pass fixed every
`EmployeeService`-routed lookup and reasoned (correctly) that most services
resolve their target through that choke point first. `HolidayService` never
did — it's one of the few services that doesn't go through
`EmployeeService` at all, so it fell outside that net entirely.

**Fix:** `HolidayService.getById` now carries the identical tenant check
`EmployeeService.getEntityById` has (404, not 403, on a cross-company id -
see finding 3's rationale). `create`/`update` now derive `companyId` from
the caller's own company rather than trusting the request body, the same
pattern used for `EmployeeRequest.companyId` since Phase 3. `getAll`/
`getBetween` are now company-scoped.

**Proof:** `TenantIsolationHttpTest.holidayCrossTenantAccessIsRejected` -
Company A's HR creates a holiday naming Company B's id (lands in Company A
anyway, verified against the DB directly since the field is
`@JsonIgnore`d); Company B's HR then gets 404 on read/update/delete of that
holiday and sees zero holidays in its own list.

---

### 2. HIGH — Attendance write-path IDOR: `correctDay` and `unlockMonth` never checked the target's tenant

**Where:** `AttendanceService.correctDay`, `unlockMonth`, `lockMonth`.

**Impact:** These three methods call `assertHrOrAdmin(actorId)`, which
resolves and checks the **caller's own** role — but never resolves the
**target** `userId` at all. `dailyAttendanceRepository.findByUserIdAndAttendanceDate`
has no company filter (it can't — `DailyAttendance` is keyed by `userId`
string, tenant-scoped only through whichever `Employee` that userId belongs
to, and nothing was resolving that `Employee`). The result: any HR/ADMIN,
authenticated as themselves in their own company, could correct or unlock
another company's attendance record by simply naming that company's
`userId` in the URL - `PUT /api/attendance/{otherCompanysUserId}/{date}`.
A locked (already-paid) period could even be unlocked and rewritten this
way.

**Why it was missed in Phase 3:** I had grepped every
`employeeService.getEntityByUserId` call site and reasoned that all of
`AttendanceService`'s userId-keyed operations were covered - true for the
*read* paths (`getDailyAttendance`, `getMonthlyAttendance`, `getRecords`,
confirmed independently again in this audit), false for the two *write*
paths, which resolve the record directly by `(userId, date)` without ever
touching `EmployeeService`.

**Fix:** Both methods now resolve the target `userId` through
`EmployeeService.getEntityByUserId` (tenant-checked) before touching
anything. This also fixed a related correctness issue for free: `correctDay`
was calling `holidayService.mandatoryHolidayDates(date, date)` with the
*global, unscoped* holiday set (see finding 3) - it now uses the target
employee's own company.

**Proof:** `TenantIsolationHttpTest.attendanceCrossTenantWriteIsRejected` -
Company A's HR gets 404 attempting to correct or unlock `EMPB001`'s
(Company B) attendance, with no attendance data needing to exist first,
since the tenant check now fires before the attendance lookup.

---

### 3. HIGH — Holiday calendars bled across companies in the attendance/scheduling engine itself

**Where:** `HolidayService.mandatoryHolidayDates`, called from 4 sites in
`AttendanceService` and 3 in `ShiftSchedulingService`.

**Impact:** This is a **functional correctness bug**, not just an
authorization gap, and arguably the most consequential finding here because
it doesn't require anyone to do anything malicious - it corrupts payroll
for perfectly legitimate use the moment a second company exists.
`mandatoryHolidayDates` queried **every company's holidays merged into one
set**, with no company argument at all. Every attendance calculation,
generation, and shift-schedule holiday-skip in the entire app was checking
a rostered day against every company's holiday calendar simultaneously - so
Company A's Diwali would silently exempt Company B's employees from work
that day too (or vice versa), corrupting both companies' working-day counts,
LOP, and payroll the moment they had non-identical calendars.

**Fix:** `mandatoryHolidayDates` now takes an explicit `Long companyId`.
Every call site already had (or could cheaply obtain) the relevant
`Employee`, so this was a mechanical threading change, not a rewrite of any
calculation logic - the date-window, night-shift-boundary, and punch logic
that `Attendance.md` documents so carefully was not touched. `null` (no
company) preserves the exact pre-fix "every company's holidays" behavior,
which is what every existing test's company-less employees already resolve
to - this is why the fix didn't break any of the 40+ existing
attendance/payroll tests with their exact-value assertions.

The trickiest call site, `ShiftSchedulingService.applyHolidayOverride`,
queries `ShiftSchedule` directly, which has no `company_id` column of its
own (see finding on `Department`/`Designation`/`Shift` below - the same
"child table keyed by userId" pattern). It's now scoped by intersecting
against `EmployeeService.getActiveEntities()`, which was already
company-scoped as of Phase 3.

**Proof:** the full existing attendance/payroll test suite (`NightShiftMonthBoundaryTest`,
`AttendanceRegularisationTest`, `PayrollFlowIntegrationTest`, the
`calculation/*` tests - all with hardcoded exact-value assertions) passes
unchanged, twice in a row, confirming the threading didn't alter any
calculation for the company-less employees those tests use. A dedicated
cross-company holiday-bleed test was not added on top of this - the
existing suite's exact-value assertions are a stronger proof than a new
assertion would be, since they'd catch *any* change in output, not just the
one this fix targets.

---

### 4. MEDIUM — `Role.ADMIN` was seeded with every permission, including platform-only ones

**Where:** `PermissionSeeder` (introduced in Phase 2, wrong from that point
until this audit).

**Impact:** `grants.put(Role.ADMIN.name(), EnumSet.allOf(PermissionCode.class))`
handed every company-scoped `ADMIN` `COMPANY_CREATE`/`UPDATE`/`DELETE` -
permissions this app's own documented authorization matrix, written the
same phase, said were platform-only. A company `ADMIN` could create,
rename, or delete *any* company on the platform, not just administer their
own - a vertical-privilege-escalation-shaped gap sitting directly against
the tenant boundary the rest of this rollout exists to enforce.

**Why it went undetected for two phases:** nothing exercised "a company
admin attempts a platform-only action" until `CompanyOnboardingHttpTest.onboardingIsPlatformOnly`
was written in Phase 4 - it failed immediately (expected 403, got 409,
because the request got far enough to hit a duplicate-company-code
conflict). This is the clearest example in this whole audit of a test
finding something a documentation cross-check wouldn't have: SECURITY.md's
matrix table was correct the whole time; the seeding code just didn't match
it.

**Fix:** `ADMIN` and `HR` now share one literal `Set<PermissionCode>`
(`companyAdminPermissions`), with `ADMIN` adding only `AUDIT_READ` on top.
Sharing the set makes "these two roles are identical except for named
exceptions" structural instead of something to keep in sync by hand -
the exact class of bug that just happened cannot recur here.

**Proof:** `CompanyOnboardingHttpTest.onboardingIsPlatformOnly`, now
passing.

---

### 5. MEDIUM — JWT audience claim was written but never validated

**Where:** `JwtService`.

**Impact:** Access tokens have carried an `aud` claim
(`"accusharp-hrms-api"`) since Phase 1, but `parseAccessToken` never called
`.requireAudience(...)` - the claim was pure decoration. Low impact *today*
(this signing key issues tokens for exactly one API), but it's the kind of
gap that turns into a real cross-service token-confusion vector the moment
the same `JWT_SECRET` is ever reused for a second service, and the original
security spec explicitly asked for audience validation. Costs nothing to
close now.

**Fix:** `.requireAudience(AUDIENCE)` added to the parser. Also fixed two
adjacent robustness issues found while in this code: a no-op
`catch (ExpiredJwtException e) { throw e; }` (dead code, removed), and a
missing/unrecognized `type` claim throwing an uncaught `NullPointerException`
/`IllegalArgumentException` instead of the `JwtException` family
`JwtAuthenticationFilter` actually catches (would have surfaced as a raw
500 instead of a clean 401 - currently unreachable since this service only
ever validates tokens it issued itself, but not defensively coded).

**Proof:** full `AuthApiHttpTest`/`AttendanceApiHttpTest` suites (which
issue and consume real tokens throughout) pass unchanged, confirming
legitimate tokens still validate correctly with the new check in place.

---

### 6. LOW — Seeded demo password was logged in plain text at INFO level

**Where:** `DataSeeder`.

**Impact:** `log.info(...SEED_PASSWORD)` printed the literal demo password
value to the application log on every startup. Low real-world severity
(the value is a fixed demo credential, already published in SECURITY.md,
never used past local/demo setup) - but it is precisely the anti-pattern
Section 11/15 of the original spec singles out ("passwords must never be
logged"), and a demo password is not an exemption from that rule, only a
reason the consequence of the mistake happens to be small this time.

**Fix:** the log line now points to SECURITY.md instead of interpolating
the value.

---

## Verified, not just assumed

Things I checked directly rather than taking on faith from earlier phases:

- **Every `@PreAuthorize` permission code matches the seeded catalog** - all
  34 codes cross-referenced by hand against `PermissionSeeder`, no typos, no
  orphaned codes.
- **Every one of the 73 controller endpoints (17 controllers) has
  authorization coverage** - endpoint-mapping counts vs. `@PreAuthorize`
  counts reconciled per controller; the 3 class-level-only controllers
  (`ReportController`, `SalarySlipController`, `AuditLogController`) checked
  by hand to confirm the class annotation actually covers every method in
  the file.
- **`@PreAuthorize` denials are actually handled by `GlobalExceptionHandler`,
  not the dead-code path I originally assumed** - verified empirically with
  a temporary marker string during the Phase 2 review, not just reasoned
  about from the Spring docs.
- **No native/raw SQL anywhere** - grep for `nativeQuery`, `jdbcTemplate`,
  `createNativeQuery` across `src/main` returns nothing; every query is
  JPQL via `@Query` with `@Param` binding or a Spring Data derived query.
  No SQL injection surface.
- **No hardcoded real secrets, private keys, or cloud credentials** in any
  tracked file - only the documented, intentional demo password constant.
- **No `System.out`/`printStackTrace`** anywhere in `src/main` - all output
  goes through SLF4J, so log level and destination are actually
  controllable in production.
- **BCrypt via Spring's own `BCryptPasswordEncoder`** for every password -
  no custom hashing, no MD5/SHA1, matches the spec's explicit requirement.
- **Refresh tokens are opaque, SHA-256-hashed at rest, single-use with
  mandatory rotation** - a database read alone can't be replayed as a
  credential, and reuse of an already-consumed token is rejected.
- **Spring Security's default response headers remain active** -
  `SecurityConfig` only customizes `frameOptions` (for the H2 console); it
  doesn't disable the framework defaults (`X-Content-Type-Options: nosniff`,
  clickjacking protection, etc.), so those still apply.

---

## Open findings (not fixed)

These are real, and deliberately left open rather than rushed - both need a
product decision or infrastructure this project doesn't have yet, not just
more code.

### HIGH (closed in Phase 6) — `Department`, `Designation`, `Shift` are still global masters shared by every company

**Closed in [SECURITY.md](SECURITY.md#multi-tenant-masters-and-reports-phase-6)**
- the finding below is kept verbatim as the historical record of what was
found and why it was left open at the time; it is no longer current state.
The recommended interim mitigation was **not** the path taken - the full
per-company migration was, per that section.

Unlike `Holiday` and `SalaryRule`, these three have no `company_id` column
at all - they were flagged as out of scope for Phase 3 because their unique
constraints are single-column (`department_code`, `shift_code`, ...), and
widening them to a composite `(company_id, code)` constraint safely on an
*existing* database needs a real migration tool. This project only has
`ddl-auto=update`, which reliably adds columns but does not reliably alter
or drop an existing unique index - see README §13, which already documents
this same limitation for the schema generally.

**Concrete risk today:** any company's HR can edit or delete a `Shift`
that a *different* company's roster depends on. Deletion is partially
guarded (`ShiftService.delete` refuses if *any* company's roster still
references it - global, so it accidentally helps here), but an in-place
**edit** to a shift's start/end time or working hours is not guarded at
all, and would silently corrupt another company's attendance the next time
it's calculated.

**Recommended interim mitigation**, if a second company goes live before
the schema migration lands: restrict `DEPARTMENT_MANAGE`/`DESIGNATION_MANAGE`/`SHIFT_MANAGE`
to platform-only in `PermissionSeeder` (one-line change per permission,
same pattern as `COMPANY_CREATE`), trading "companies can self-serve their
own shift definitions" for "no company can corrupt another's." This is a
product tradeoff, not a technical one - I did not make it unilaterally.

### MEDIUM (closed in Phase 6) — Most list/report endpoints still return cross-company data

**Closed in [SECURITY.md](SECURITY.md#multi-tenant-masters-and-reports-phase-6)**
- kept verbatim as the historical record; no longer current state.

`EmployeeService.getAll()`, every `ReportService.*` method,
`DashboardService`, `PayrollService.getPeriod`, `HolidayService`'s two
remaining unscoped internal callers (none currently reachable
unscoped from HTTP, per the audit above) - these return every company's
records mixed together to any caller holding the relevant `_READ`
permission, which for several of these (e.g. `EMPLOYEE_READ`) is every
role including plain `EMPLOYEE`. This was known and documented as deferred
after Phase 3; re-confirmed still true and still the single largest
remaining piece of "true" multi-tenant isolation. Fixing it exhaustively
was out of scope for an audit pass - it's a design-and-implement task on
the scale of Phase 3 itself, touching a dozen-plus methods across
`ReportService`/`DashboardService` that don't yet have a natural
`TenantContext` choke point the way single-resource lookups did.

### LOW (accepted, not a defect) — Residual, bounded risks that are working as designed

- **A disabled/locked account's existing access token remains valid until
  it naturally expires** (≤ 15 minutes) - stateless JWT access tokens are
  validated by signature alone, not a database check. This is a deliberate,
  documented tradeoff (short expiry is the mitigation), not an oversight.
- **No rate limiting on `/api/auth/login`** beyond per-account lockout after
  5 failed attempts - an attacker can still spread guesses across many
  accounts without tripping any single account's lockout, or exhaust the
  server with repeated BCrypt hashing (CPU cost). IP-based rate limiting is
  infrastructure-layer work (a reverse proxy or API gateway concern) more
  than an application-code fix, and wasn't attempted here.
- **No dependency-vulnerability scanning wired into the build** (no OWASP
  Dependency-Check/Snyk/etc.) - dependency versions were checked by eye
  (Spring Boot 4.1.0, jjwt 0.12.6, both current), but there's no automated,
  ongoing check for newly-disclosed CVEs in the dependency tree.
- **No forced password change on first login** after `CompanyOnboardingService`
  issues a temporary password - documented as a follow-up since Phase 4;
  re-confirmed still open, still small (a boolean flag + one check in
  `AuthService`), still not done.

---

## Test coverage added this pass

| Test | Proves |
|---|---|
| `TenantIsolationHttpTest.holidayCrossTenantAccessIsRejected` | Finding 1 (Holiday IDOR) is fixed |
| `TenantIsolationHttpTest.attendanceCrossTenantWriteIsRejected` | Finding 2 (Attendance write IDOR) is fixed |
| `CompanyOnboardingHttpTest.onboardingIsPlatformOnly` (pre-existing, Phase 4) | Finding 4 (ADMIN over-grant) is fixed - this test is what originally caught it |

73 tests total, 0 failures, verified stable across two consecutive full runs
(ruling out execution-order flakiness, which one of the fixes in this pass
briefly introduced and then corrected - see the `CompanyOnboardingHttpTest`
cleanup-ordering note in its own file Javadoc).

## Files touched in this audit pass

```
security/JwtService.java                    audience validation, dead-code removal, defensive claim parsing
security/JwtAuthenticationFilter.java        stale Javadoc corrected
service/HolidayService.java                  tenant check + scoping (finding 1)
repository/HolidayRepository.java            company-scoped query methods
service/attendance/AttendanceService.java    correctDay/unlockMonth/lockMonth tenant checks (finding 2),
                                              mandatoryHolidayDates company-threading (finding 3)
service/shift/ShiftSchedulingService.java    mandatoryHolidayDates company-threading (finding 3)
config/PermissionSeeder.java                 ADMIN/HR shared permission set (finding 4)
config/DataSeeder.java                       stopped logging the demo password (finding 6)
dto/LoginRequest.java, ChangePasswordRequest.java   added max-length bounds
test/.../TenantIsolationHttpTest.java        2 new tests (findings 1, 2)
test/.../CompanyOnboardingHttpTest.java      test-isolation cleanup-order fix
```

Nothing in this pass changed any public API contract, request/response
shape, or existing business rule - every fix closes a gap in *enforcement*
of rules the app already claimed to have.
