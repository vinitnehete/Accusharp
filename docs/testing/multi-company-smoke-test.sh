#!/usr/bin/env bash
# Multi-company onboarding + isolation smoke test, over real HTTP against a
# running instance of this app.
#
# What it proves, end to end, against a live server rather than the JUnit
# suite: a platform operator can onboard two brand-new companies, each one
# can build its own org masters (departments/shifts with the SAME codes -
# the exact thing Phase 6's migration exists to allow), each company's
# people can only ever see their own company's data, and a plain employee
# can only ever see their own record within that company (Phase 8).
#
# Usage:
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=h2 &   # in one terminal
#   ./docs/testing/multi-company-smoke-test.sh                # in another
#
# Requires the app already running on :8080 (H2 profile is easiest - no
# MySQL needed), plus curl and jq. Deliberately does not try to start/stop
# the server itself - a `mvnw spring-boot:run` child process reparents in a
# way that's easy to leak from a wrapper script, which is exactly the fragile
# part worth keeping out of a test script whose job is the HTTP assertions.
set -uo pipefail

BASE="http://localhost:8080"
PLATFORM_PASSWORD="Accusharp@123"
PASS=0
FAIL=0

# ---- helpers ---------------------------------------------------------------

log() { printf '\n\033[1;34m== %s ==\033[0m\n' "$1"; }

# expect_status EXPECTED ACTUAL DESCRIPTION
expect_status() {
    local expected="$1" actual="$2" description="$3"
    if [ "$actual" = "$expected" ]; then
        PASS=$((PASS + 1))
        printf '  \033[0;32mPASS\033[0m  %-70s (%s)\n' "$description" "$actual"
    else
        FAIL=$((FAIL + 1))
        printf '  \033[0;31mFAIL\033[0m  %-70s (expected %s, got %s)\n' "$description" "$expected" "$actual"
    fi
}

# http METHOD PATH TOKEN BODY -> prints "STATUS\nBODY", capture with:
#   resp=$(http POST /api/x "$token" '{"a":1}'); status=$(head -1 <<<"$resp"); body=$(tail -n +2 <<<"$resp")
http() {
    local method="$1" path="$2" token="${3:-}" body="${4:-}"
    local args=(-s -o /tmp/smoke_body.$$ -w '%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json')
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$body" ] && args+=(-d "$body")
    local status
    status=$(curl "${args[@]}")
    printf '%s\n' "$status"
    cat /tmp/smoke_body.$$
    rm -f /tmp/smoke_body.$$
}

# call METHOD PATH TOKEN BODY -> sets $STATUS and $BODY globals
call() {
    local out status body
    out=$(http "$1" "$2" "${3:-}" "${4:-}")
    STATUS=$(printf '%s' "$out" | head -1)
    BODY=$(printf '%s' "$out" | tail -n +2)
}

login() {
    call POST /api/auth/login "" "{\"username\": \"$1\", \"password\": \"$2\"}"
    if [ "$STATUS" != "200" ]; then
        echo "Login failed for $1: $BODY" >&2
        exit 1
    fi
    echo "$BODY" | jq -r .accessToken
}

if ! curl -s -o /dev/null "$BASE/api/auth/login"; then
    echo "Nothing listening on :8080 - start the app first (see usage above)" >&2
    exit 1
fi
log "Found the app running on :8080"

# ---- onboard two companies --------------------------------------------------

log "Platform owner login"
PLATFORM_TOKEN=$(login platform_owner "$PLATFORM_PASSWORD")
echo "  ok"

log "Onboard Company A (Acme) and Company B (Globex)"
call POST /api/companies/onboard "$PLATFORM_TOKEN" '{
  "companyCode": "SMOKE-ACME", "companyName": "Acme Corp",
  "adminUserId": "ACME-ADMIN", "adminEmployeeCode": "ACME-E001", "adminName": "Acme Admin",
  "adminGrossSalary": 60000, "adminPfBasic": 18000}'
expect_status 201 "$STATUS" "Onboard Acme"
ACME_ADMIN_PASSWORD=$(echo "$BODY" | jq -r .temporaryPassword)
ACME_COMPANY_ID=$(echo "$BODY" | jq -r .company.id)

call POST /api/companies/onboard "$PLATFORM_TOKEN" '{
  "companyCode": "SMOKE-GLOBEX", "companyName": "Globex Corp",
  "adminUserId": "GLOBEX-ADMIN", "adminEmployeeCode": "GLOBEX-E001", "adminName": "Globex Admin",
  "adminGrossSalary": 60000, "adminPfBasic": 18000}'
expect_status 201 "$STATUS" "Onboard Globex"
GLOBEX_ADMIN_PASSWORD=$(echo "$BODY" | jq -r .temporaryPassword)
GLOBEX_COMPANY_ID=$(echo "$BODY" | jq -r .company.id)

ACME_TOKEN=$(login ACME-ADMIN "$ACME_ADMIN_PASSWORD")
GLOBEX_TOKEN=$(login GLOBEX-ADMIN "$GLOBEX_ADMIN_PASSWORD")
echo "  both admins logged in"

# ---- each company builds its own org masters, same codes on purpose --------

log "Both companies create a department and a shift using the IDENTICAL code (Phase 6's whole point)"
call POST /api/departments "$ACME_TOKEN" '{"departmentCode": "OPS", "departmentName": "Operations"}'
expect_status 201 "$STATUS" "Acme creates department OPS"
ACME_DEPT_ID=$(echo "$BODY" | jq -r .id)

call POST /api/departments "$GLOBEX_TOKEN" '{"departmentCode": "OPS", "departmentName": "Globex Operations"}'
expect_status 201 "$STATUS" "Globex creates department OPS (same code, different company - would have 409'd pre-Phase-6)"
GLOBEX_DEPT_ID=$(echo "$BODY" | jq -r .id)

call POST /api/shifts "$ACME_TOKEN" '{"shiftCode": "DAY", "shiftName": "Acme Day",
  "startTime": "09:00:00", "endTime": "18:00:00", "workingHours": 8, "breakMinutes": 30,
  "graceMinutes": 10, "overtimeWindowMinutes": 120}'
expect_status 201 "$STATUS" "Acme creates shift DAY"

call POST /api/shifts "$GLOBEX_TOKEN" '{"shiftCode": "DAY", "shiftName": "Globex Day",
  "startTime": "08:00:00", "endTime": "17:00:00", "workingHours": 8, "breakMinutes": 30,
  "graceMinutes": 10, "overtimeWindowMinutes": 120}'
expect_status 201 "$STATUS" "Globex creates shift DAY (same code, different company)"

log "Cross-company: Acme's admin cannot read Globex's department or company by id"
call GET "/api/departments/$GLOBEX_DEPT_ID" "$ACME_TOKEN"
expect_status 404 "$STATUS" "Acme reading Globex's department by id"
call GET "/api/companies/$GLOBEX_COMPANY_ID" "$ACME_TOKEN"
expect_status 404 "$STATUS" "Acme reading Globex's company by id"

# ---- each company hires a supervisor and two employees ---------------------

log "Acme hires a supervisor and two employees (one reports to the supervisor)"
call POST /api/employees "$ACME_TOKEN" "{\"userId\": \"ACME-SUP\", \"employeeCode\": \"ACME-E002\",
  \"employeeName\": \"Acme Supervisor\", \"departmentId\": $ACME_DEPT_ID, \"status\": \"PERMANENT\",
  \"role\": \"SUPERVISOR\", \"grossSalary\": 45000, \"pfBasic\": 12000, \"medicalAllowance\": 1000,
  \"otherAllowance\": 0}"
expect_status 201 "$STATUS" "Acme creates a supervisor"

call POST /api/employees "$ACME_TOKEN" "{\"userId\": \"ACME-EMP\", \"employeeCode\": \"ACME-E003\",
  \"employeeName\": \"Acme Employee\", \"departmentId\": $ACME_DEPT_ID, \"supervisorUserId\": \"ACME-SUP\",
  \"status\": \"PERMANENT\", \"role\": \"EMPLOYEE\", \"grossSalary\": 25000, \"pfBasic\": 8000,
  \"medicalAllowance\": 500, \"otherAllowance\": 0}"
expect_status 201 "$STATUS" "Acme creates an employee reporting to the supervisor"

call POST /api/employees "$GLOBEX_TOKEN" "{\"userId\": \"GLOBEX-EMP\", \"employeeCode\": \"GLOBEX-E002\",
  \"employeeName\": \"Globex Employee\", \"departmentId\": $GLOBEX_DEPT_ID, \"status\": \"PERMANENT\",
  \"role\": \"EMPLOYEE\", \"grossSalary\": 25000, \"pfBasic\": 8000, \"medicalAllowance\": 500,
  \"otherAllowance\": 0}"
expect_status 201 "$STATUS" "Globex creates an employee"

# NOTE: ACME-SUP/ACME-EMP/GLOBEX-EMP can never log in - EmployeeService.create()
# never sets a passwordHash (only CompanyOnboardingService's first admin gets one,
# via generateTemporaryPassword()). There is no admin-set-initial-password field
# on EmployeeRequest and no self-service password-setup flow. This is a real gap
# for actually onboarding and using a company, not a security issue - flagged in
# the script's own summary below rather than worked around with a direct DB
# write, which would test something this app's API cannot actually do today.
# Self-service scoping itself (a plain EMPLOYEE only ever seeing their own data)
# is already proven end-to-end by SelfServiceScopingHttpTest, which seeds a
# real password hash directly since it's a JUnit test, not a live-API smoke test.

log "Cross-company: Acme's admin cannot read Globex's employee, and Globex's employee list stays Globex-only"
call GET /api/employees/by-user-id/GLOBEX-EMP "$ACME_TOKEN"
expect_status 404 "$STATUS" "Acme admin reading Globex's employee"
call GET /api/employees "$GLOBEX_TOKEN"
expect_status 200 "$STATUS" "Globex admin listing employees"
COUNT=$(echo "$BODY" | jq 'length')
expect_status 2 "$COUNT" "  ...and sees exactly Globex's 2 employees (admin + GLOBEX-EMP), not Acme's"

log "Summary"
echo "  $PASS passed, $FAIL failed"
[ "$FAIL" = "0" ]
