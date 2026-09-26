# The attendance policy engine — design

Configurable attendance policy per employee population, on top of the pipeline
[Attendance.md](../../Attendance.md) describes. Read that first; this document
assumes it and changes nothing in it.

**Status: built.** This document is the design that was approved; the section
at the bottom records where the implementation deviated from it and why. See
`Attendance.md` section 11 for the operator-facing version.

---

## The model in one line

```
today:  one company  ->  one policy  ->  everyone
after:  one company  ->  a stack of typed rules, resolved per employee per date
```

Two ideas carry the design, and both are borrowed from what already works here:

1. **A rule is a typed record with a fixed effect, not a formula.** The catalog
   is an enum. Adding a rule type is a code change and a migration. That is the
   feature: an eval-able string in a database column decides salaries, cannot be
   tested, cannot be migrated, and cannot be explained back to an employee
   disputing a deduction.
2. **A version is never edited, only succeeded.** Rules are append-only and
   resolved *as of the attendance date*, exactly the way `SalaryRevision` and
   `SalaryStructureRevision` already record how an employee's pay got where it
   is. An August day is priced by the rule that was in force in August, whatever
   HR does in September.

---

## 1. How it works today

Every place a status, a day-fraction, or an LOP day is decided. The engine has
to enter this list at exactly two points and nowhere else; the value of writing
it out is knowing which fifteen places it must *not* enter.

### A day's status

| # | Where | What it decides |
|---|---|---|
| 1 | `AttendanceCalculationService.resolveNonWorkingStatus` | Fewer than two punches: `INVALID_PUNCH` (exactly 1) else leave > holiday > weekly off > `ABSENT` |
| 2 | `AttendanceCalculationService.resolveWorkedStatus` | Two or more punches: weekly off/holiday ⇒ `PRESENT`; else `fullDayThresholdPercent`/`halfDayThresholdPercent` of the shift's paid minutes |
| 3 | `AttendanceService.correctDay` | Mode 1: recomputes via `calculateDay`, then an explicit `request.status` overrides the derived one |
| 4 | `AttendanceService.applyForcedStatus` | Mode 2: the status HR declared, with hours following the shift and late/early/overtime zeroed |
| 5 | `AttendanceService.getDailyAttendance`, `.previewMonth` | Unstored days only — computed for the read, persisted nowhere |

Statuses 1 and 2 are the *generated* status; 3 and 4 are the *human* status.
Everything in this design touches 1 and 2 and must never touch 3 and 4.

### A day's fraction

| # | Where | What it decides |
|---|---|---|
| 6 | `AttendanceCalculationService.dayFraction` | `PRESENT` 1.0, `HALF_DAY` 0.5, everything else 0 |
| 7 | `AttendanceService.aggregate` | `presentDays` = Σ `dayFraction`, **counting only dates in `workingDates`** |
| 8 | `LeaveDuration.getDayFraction` → `LeaveCalculationService.countDays` / `.totalLeaveDays` / `.paidLeaveDays` | Leave fractions, also filtered to `workingDates` |
| 9 | `AttendanceService.applyForcedStatus` | Hours, not fraction — `PRESENT` ⇒ shift hours, `HALF_DAY` ⇒ half |

### The working-day denominator

| # | Where | What it decides |
|---|---|---|
| 10 | `DailyAttendance.isWorkingDay()` | `!weekOff && !holiday`, off the **snapshotted** flags |
| 11 | `AttendanceService.workingDatesOf` | Stored path: the set of expected days, read off stored rows so a later roster edit cannot restate a paid month |
| 12 | `AttendanceService.previewMonth` | Preview path: the same set, computed live from roster + holidays |

### An LOP day

| # | Where | What it decides |
|---|---|---|
| 13 | `LopCalculationService.calculateLopDays` | `max(0, workingDays − presentDays − paidLeaveDays)`, scale 1, `HALF_UP` |
| 14 | `AttendanceService.aggregate` | Calls 13 — **the one place month figures are derived**, for both the stored and the preview path |
| 15 | `AttendanceService.rebuildSummary` | Persists 14 onto `emp_monthly_attendance_summary` |
| 16 | `PayrollService.build` | `lopDays = attendance.getLopDays()` — or hard `ZERO` for `DAY_WISE`, which is paid per attended day |
| 17 | `LopCalculationService.calculatePayableDays` + `.min(window.days())` in `PayrollService.build` | `payableDays`, capped by the employed window |
| 18 | `DeductionCalculationService.calculateLopDeduction` | The rupee value of the unpaid days — shown on the slip, not added to the deduction total |

### What is recorded and then ignored

`DailyAttendance.lateMinutes` and `.earlyExitMinutes` are computed in
`AttendanceCalculationService.calculateDay`, rolled into
`MonthlyAttendanceSummary.lateCount` / `.earlyExitCount` by
`AttendanceService.aggregate`, and **read by nothing else in the application**.
No status, no fraction, no LOP day, no rupee depends on either. That is the hole
this feature fills.

**And one more, found while writing this.** `overtimeMinutes = max(0,
workedMinutes - workingHours * 60)` is applied uniformly, including on a weekly
off or holiday that was worked. A full eight-hour shift worked on a Sunday
therefore books **five minutes** of overtime, and adds nothing to `presentDays`
because the day is not in `workingDates`. Attendance.md's "the employee is paid
overtime for it, not a day's wage" describes an intent the arithmetic does not
implement. Nothing in this design changes it — but scenario G is configured
against it, so §8 G says so plainly rather than letting the comparison look
better than it is.

### Three call sites that matter more than they look

- **`ReportService.payrollReport` and `.attendanceLeaveReport` both call
  `attendanceService.syncSummaries(month)`.** Running a report rewrites the
  stored summary of every employee for that month — including months that are
  locked and paid. `rebuildSummary` persists; `aggregateStored` (the
  `GET /monthly` path) does not. So the "would an edited rule silently re-price
  a paid month?" question in §3.4 of the brief is not theoretical: **a report
  is enough to trigger it.** §7 deals with this.
- **`PayrollService.build` calls `getGeneratedSummary`, which calls
  `rebuildSummary` unconditionally** before locking the month.
- **`AttendanceService.generateFor` resolves from `first.minusDays(1)`**, so the
  last night shift of the previous month is re-partitioned and can be rewritten
  by this month's run. Anything month-scoped must therefore be prepared to be
  rebuilt for two months by one generation call — `generateFor` already does
  exactly this with `previousMonthTouched`.

---

## 2. The rule model

```java
enum RuleScope { EMPLOYEE, DESIGNATION, CATEGORY, DEPARTMENT, EMPLOYMENT_TYPE, COMPANY, GLOBAL }
enum RuleEvaluationScope { DAY, MONTH }
enum RuleType { ... }   // below
```

Every rule row is `(company, scope, scopeRef, ruleType, version, effectiveFrom,
enabled, params)`. `params` is a JSON string deserialised into **one record type
per `RuleType`** and bean-validated at the boundary. It is never read as a map,
and never read loosely at evaluation time — see §10 for what happens when a blob
fails to parse.

### The catalog

| `RuleType` | Scope | Params (typed) | Effect |
|---|---|---|---|
| `LATE_ARRIVAL` | DAY | `graceMinutes:int 0..720`, `penaltyStatus:{HALF_DAY,ABSENT}` | Recomputes `lateMinutes` against its own grace instead of `Shift.graceMinutes`; if late, **status override** downward |
| `SHORT_HOURS` | DAY | `basis:{PERCENT_OF_SHIFT,ABSOLUTE_MINUTES}`, `fullDayValue:BigDecimal`, `halfDayValue:BigDecimal` | Replaces `AttendanceRule`'s two thresholds for this population. **Status override** |
| `MISSING_PUNCH` | DAY | `fallbackStatus:{HALF_DAY,ABSENT}`, `onTimeGraceMinutes:int` | On a day holding exactly one punch, if that punch falls inside the entry window, **status override** from `INVALID_PUNCH` |
| `OVERTIME` | DAY | `payable:boolean`, `minimumMinutes:int`, `roundingBlockMinutes:int`, `rounding:{DOWN}` | **Adjusts `overtimeHours`**. No status effect |
| `DAY_OFF_WORK` | DAY | `onWeeklyOff:{OVERTIME_PAY,COMP_OFF_CREDIT}`, `onHoliday:{...}`, `fullCreditMinutes:int`, `halfCreditMinutes:int` | On a worked weekly off/holiday: zeroes `overtimeHours` and **emits a comp-off credit** instead. No status effect |
| `EARLY_EXIT_BUDGET` | MONTH | `monthlyBudgetMinutes:int`, `penaltyDaysPerOccurrence:BigDecimal` | Budget consumed in date order; each early exit *after* it is exhausted is an **LOP delta** |
| `LATE_MARK_ACCUMULATION` | MONTH | `minimumLateMinutes:int`, `occurrencesPerPenalty:int ≥1`, `penaltyLopDays:BigDecimal` | `floor(marks / N) × penalty` — an **LOP delta** |

Five effects exist and no others: status override, overtime adjustment,
comp-off credit, LOP delta, nothing. A rule cannot invent a new kind of
consequence without a new enum constant and a new column, which is the point.

### `enabled = false` is the opt-out, and it is the only one

Scenario D's "managers are not tracked for lateness at all" is a
`LATE_ARRIVAL` row at `CATEGORY=MANAGER` with `enabled = false`. Resolution
still picks it — most specific wins — and finding it disabled means **this rule
type does not apply to this population**, which is precisely today's behaviour:
`lateMinutes` is still recorded, and still has no consequence. There is
deliberately no second "suppress" parameter saying the same thing a different
way.

### Nothing is seeded

The `company = null` GLOBAL rows exist as a scope, and the table ships **empty**.
This is the opposite of `AttendanceRule.defaultRule()`, and deliberately so:
a seeded global `LATE_ARRIVAL` would change every existing tenant's pay on
deploy. The global scope is a hook for a future shared catalog, not a default.

---

## 3. The data model

### `attendance_policy_rule`

```sql
CREATE TABLE attendance_policy_rule (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    company_id      BIGINT       NULL,           -- NULL = global; only a platform caller writes these
    scope           VARCHAR(20)  NOT NULL,       -- RuleScope
    scope_ref       VARCHAR(50)  NOT NULL,       -- see below; '*' for COMPANY and GLOBAL
    rule_type       VARCHAR(40)  NOT NULL,       -- RuleType
    version         INT          NOT NULL,       -- 1, 2, 3... within the chain
    effective_from  DATE         NOT NULL,
    enabled         BIT(1)       NOT NULL,
    params          TEXT         NOT NULL,       -- JSON, parsed into the type's record
    created_at      DATETIME(6)  NOT NULL,
    created_by      VARCHAR(50)  NULL,
    notes           VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_policy_rule_effective
        UNIQUE (company_id, scope, scope_ref, rule_type, effective_from),
    CONSTRAINT uk_policy_rule_version
        UNIQUE (company_id, scope, scope_ref, rule_type, version),
    KEY idx_policy_rule_lookup (company_id, rule_type, scope, scope_ref, effective_from)
);
```

**`scope_ref` is `NOT NULL` with a `'*'` sentinel**, not nullable. MySQL and H2
both treat `NULL`s in a unique index as distinct, so a nullable `scope_ref`
would let two company-scoped rules of the same type and date coexist — the
exact race `SalaryRule`'s Javadoc already documents a service-layer
check-then-act cannot close. The sentinel makes the constraint bite.

`scope_ref` holds:

| Scope | `scope_ref` |
|---|---|
| `GLOBAL`, `COMPANY` | `'*'` |
| `DEPARTMENT`, `DESIGNATION`, `CATEGORY` | the **code** (`departmentCode`, `categoryCode`, ...) |
| `EMPLOYMENT_TYPE` | `EmployeeStatus.name()` — `PERMANENT`, `DAY_WISE`, `CONTRACT`, `INTERN` |
| `EMPLOYEE` | `Employee.userId` |

Codes, not ids, for the three masters: they are unique per company since
Phase 6 (`uk_category_company_code`), they read correctly in an explanation
(`CATEGORY=STAFF`, which is what an employee disputing a deduction needs to
see), and a company's visible set — its own rows plus the shared catalog — is
collision-free because `create` already checks both. `userId` for `EMPLOYEE`
matches every other attendance table in the app: `emp_daily_attendance`,
`emp_attendance_shift` and `leave_balance` all key on the `userId` string.

> **Still not fully constrained.** With `company_id` nullable, two `GLOBAL` rows
> of the same type and `effective_from` both carry `company_id = NULL` and the
> unique index will not stop them. Every per-company row — which is every row a
> tenant can create — is fully covered. The residual hole is closed by a service
> check only, and only a platform caller can reach it. Naming this rather than
> claiming otherwise, in the same spirit as Attendance.md's note on the
> unvalidated rest gap.

### Why succession, and not `effectiveFrom` + `effectiveTo`

The brief asks for `effectiveFrom`/`effectiveTo` with uniqueness on
`(scope, scope_ref, rule_type, effective_window)`. **I would not build that, and
this is my one substantive disagreement with the brief.** No relational database
in this stack can enforce non-overlap of two date ranges with a unique index —
that is PostgreSQL's `EXCLUDE`, which MySQL 8 does not have. A from/to model
therefore leaves the *most* dangerous invariant in the whole feature ("two
contradictory rules must never both apply to one day") enforced by a
service-layer check, which is exactly the arrangement `SalaryRule`'s Javadoc
already records as insufficient.

Drop `effectiveTo` and the problem disappears. A version is in force from its
`effectiveFrom` until the next version's `effectiveFrom`; the last one runs
open-ended. Overlap becomes **structurally impossible**, and the constraint the
brief asked for — `UNIQUE (company_id, scope, scope_ref, rule_type,
effective_from)` — stops being merely necessary and becomes sufficient. Ending a
policy is appending a version with `enabled = false`, which is the same act as
any other change and needs no second mechanism.

It also makes the table append-only, which is what `SalaryRevision` and
`SalaryStructureRevision` already do for pay, and for the same reason: the
question asked six months later is *what was the rule then*, and a mutable
`effectiveTo` answers it worse than a new row does.

### `attendance_policy_application` — the day trace

```sql
CREATE TABLE attendance_policy_application (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          VARCHAR(50)  NOT NULL,
    attendance_date  DATE         NOT NULL,
    rule_id          BIGINT       NOT NULL,
    rule_type        VARCHAR(40)  NOT NULL,
    rule_version     INT          NOT NULL,
    scope            VARCHAR(20)  NOT NULL,
    scope_ref        VARCHAR(50)  NOT NULL,
    status_before    VARCHAR(20)  NULL,
    status_after     VARCHAR(20)  NULL,
    overtime_before  DECIMAL(6,2) NULL,
    overtime_after   DECIMAL(6,2) NULL,
    comp_off_credit  DECIMAL(4,2) NULL,
    explanation      VARCHAR(400) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_policy_application_day (user_id, attendance_date)
);
```

Keyed on `(user_id, attendance_date)` — the same business key
`emp_daily_attendance` is unique on — not on a foreign key to its `id`. A
generation run deletes this day's rows and reinserts, so the trace is rebuilt
from scratch with the day it explains and can never drift from it. Locked and
manual days are not regenerated, so their traces are not touched either: the
trace inherits the protection the day already has, rather than needing its own.

### `attendance_policy_outcome` — the month trace

```sql
CREATE TABLE attendance_policy_outcome (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       VARCHAR(50)  NOT NULL,
    `month`       VARCHAR(7)   NOT NULL,
    rule_id       BIGINT       NOT NULL,
    rule_type     VARCHAR(40)  NOT NULL,
    rule_version  INT          NOT NULL,
    scope         VARCHAR(20)  NOT NULL,
    scope_ref     VARCHAR(50)  NOT NULL,
    lop_days      DECIMAL(4,1) NOT NULL,
    explanation   VARCHAR(400) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_policy_outcome_month (user_id, `month`)
);
```

`explanation` is a rendered sentence stored alongside the numbers, not derived
on read. It is denormalised on purpose, for the same reason `Payroll` snapshots
`rule_pf_percent` instead of joining to `salary_rule`: the answer to "why was I
docked half a day in March" must survive the rule being superseded four times
since.

### Two columns on `emp_monthly_attendance_summary`

```sql
ALTER TABLE emp_monthly_attendance_summary
    ADD COLUMN policy_lop_days DECIMAL(6,1) NOT NULL DEFAULT 0.0,
    ADD COLUMN comp_off_credit_days DECIMAL(6,1) NOT NULL DEFAULT 0.0;
```

`lop_days` keeps its meaning — **the total payroll reads** — and gains
`policy_lop_days` beside it so the base figure and the policy penalty stay
separately legible. `PayrollService` needs no change at all.

### Migration

`docs/migrations/2026-09-01-attendance-policy-engine.sql`, forward-only, in the
house style. `ddl-auto=update` creates the three new tables and both new columns
by itself; the file exists for what it will not do:

- `NOT NULL DEFAULT 0.0` on the two new summary columns — Hibernate adds them
  nullable against a populated table, and `PayrollService` reads
  `getLopDays()` into `BigDecimal` arithmetic where a `NULL` is an NPE on the
  first report after deploy. Same failure `DailyAttendance.version`'s Javadoc
  already documents for `row_version`, and the same fix.
- The `idx_policy_rule_lookup` covering index.
- Confirming both unique constraints exist under the names above.
- No data backfill. The rule table ships empty; that is what makes §6's
  regression guarantee hold.

---

## 4. Resolution

For an employee `E`, a date `D`, and a rule type `T`:

```
1. candidates = rules WHERE company = E.company
                  AND rule_type = T
                  AND effective_from <= D
                  AND scope/scope_ref matches E
2. per scope, keep only the greatest effective_from   (the version in force on D)
3. take the single most specific scope that produced a row:
      EMPLOYEE > DESIGNATION > CATEGORY > DEPARTMENT > EMPLOYMENT_TYPE > COMPANY > GLOBAL
4. if that row has enabled = false -> no rule of type T applies to E on D
5. otherwise it is the one rule of type T, and there is no other
```

**Most specific wins outright — rules never merge.** A `CATEGORY` rule does not
inherit unspecified parameters from the `COMPANY` rule above it; it replaces it
whole. Partial inheritance would mean the effective policy for an employee is
not any row anybody wrote, which is unexplainable by construction.

Step 3 returns at most one row because step 2 leaves at most one per scope, and
step 2 is guaranteed by `uk_policy_rule_effective` plus succession — not by a
service check.

### A worked collision

Employee `SE10012`: `CATEGORY = STAFF`, `DEPARTMENT = OPS`,
`EMPLOYMENT_TYPE = PERMANENT`. Date `2026-09-14`. Type `LATE_ARRIVAL`.

| Row | Scope | `scope_ref` | v | `effectiveFrom` | `enabled` | grace |
|---|---|---|---|---|---|---|
| a | COMPANY | `*` | 1 | 2026-01-01 | true | 5 |
| b | DEPARTMENT | `OPS` | 1 | 2026-04-01 | true | 20 |
| c | CATEGORY | `STAFF` | 1 | 2026-06-01 | true | 10 |
| d | CATEGORY | `STAFF` | 2 | 2026-09-01 | true | 15 |
| e | CATEGORY | `STAFF` | 3 | 2026-10-01 | true | 30 |
| f | DESIGNATION | `TEAM-LEAD` | 1 | 2026-02-01 | true | 0 |

- Step 1 drops **e** (`effective_from` 2026-10-01 > D) and **f** (`SE10012` is
  not a `TEAM-LEAD`).
- Step 2 leaves **a**, **b**, and — of c and d — **d**, the later
  `effective_from` still on or before D.
- Step 3: `CATEGORY` outranks `DEPARTMENT` outranks `COMPANY`. **d wins.**
- Grace is **15 minutes**. The department's 20 and the company's 5 are not
  consulted, blended, or averaged.

Run the same query for `2026-08-20` and **c** wins with 10 minutes; for
`2026-10-05`, **e** with 30. That is the whole of effective dating: the date
being priced picks the row, and nothing about *now* enters it.

---

## 5. The evaluation pipeline

```
                                             +-- DAY rules evaluate HERE
                                             v
device_logs -> AttendanceWindowResolver -> AttendanceCalculationService.calculateDay
                                                    |
                                                    v
                                          DailyAttendance (stored)
                                                    |
                                                    v
                              AttendanceService.aggregate  <-- MONTH rules replay HERE
                                                    |
                                       rebuildSummary persists
                                                    |
                                                    v
                              MonthlyAttendanceSummary.lopDays -> PayrollService
```

### Day-scoped: inside `calculateDay`, at its tail

`AttendanceCalculationService.calculateDay` gains a resolved
`PolicyBundle` parameter beside the `AttendanceRule` it already takes, and
applies day rules **after** deriving the base figures, returning both the
adjusted day and its trace.

Why there and not in `AttendanceService`: because `correctDay`'s mode 1 calls
`calculateDay` directly, and Attendance.md's promise is that "a hand-fixed day
derives its hours, lateness and overtime by identical rules — there is no second
code path that can drift." Putting policy in the caller would create precisely
that second path, and a mode-1 correction would silently obey a different policy
from a device-read day. The midnight-crossing logic stays where it is and rules
ask `scheduledEnd`/`crossesMidnight` for it rather than re-deriving it.

**Order within a day is fixed by `RuleType` declaration order** and is total:

```
MISSING_PUNCH  ->  SHORT_HOURS  ->  LATE_ARRIVAL  ->  DAY_OFF_WORK  ->  OVERTIME
   (status)        (status)         (status)         (overtime,      (overtime)
                                                      comp-off)
```

`MISSING_PUNCH` first because it operates on the one-punch branch the others
never see. `SHORT_HOURS` before `LATE_ARRIVAL` because lateness modifies a
status that hours have already decided. `OVERTIME` last because it reads a
`DAY_OFF_WORK` outcome that may have zeroed it.

**The monotonicity invariant.** After every day rule has run,

```
dayFraction(finalStatus) <= dayFraction(baseStatus)
```

with exactly one exception: `MISSING_PUNCH`, which raises `INVALID_PUNCH` (0.0)
to `HALF_DAY` (0.5), because that is the entire purpose of scenario H. Every
other rule may only take value away. A rule that could raise a day above what
its punches earned would be a way to configure unearned pay, and there is no
scenario in the brief that needs one. This is asserted as a test, not just
stated.

### Month-scoped: inside `aggregate`, over the days it was handed

`AttendanceService.aggregate` — whose Javadoc already says it is "the one place
month-level figures are derived, stored or preview" — replays month rules over
its `List<DailyAttendanceResponse>` sorted ascending by date, and adds the
result to `lopDays`.

Why `aggregate` and not `rebuildSummary`: `rebuildSummary` and `aggregateStored`
(the read path) both funnel through `aggregate`. Hooking the persisting one only
would make `GET /{userId}/monthly` disagree with the stored summary payroll
pays from — two numbers for one month, differing by exactly the penalty.

`rebuildSummary` persists `policyLopDays` and rewrites the outcome rows;
`aggregateStored` and `previewMonth` compute and return without writing, holding
the "a read never writes" invariant intact.

The final figure is clamped:

```
lopDays = min(workingDays, baseLop + policyLop)
```

Without the clamp a badly-configured accumulation rule pushes `lopDays` above
`workingDays`, and while `calculatePayableDays` already floors payable days at
zero, a slip reading "26 working days, 31 LOP days" is not something to print.

---

## 6. Determinism and idempotency

**The property.** For a fixed set of punches, roster, holidays, leave and rule
rows, running generation and summary rebuild any number of times, in any order,
over any subset of days, produces identical stored rows and an identical
summary.

The argument, in four parts:

1. **Day rules are pure.** Each is a function of `(this day's punches, this
   day's shift, this day's flags, the resolved rule version)`. No day rule reads
   another day, a counter, or a clock. Regenerating 12 September alone therefore
   cannot differ from regenerating the whole month.
2. **Month rules hold no state between runs.** The budget accumulator and the
   late-mark counter are local variables inside one `aggregate` call, seeded at
   zero, folded over the days in `attendanceDate` order. Nothing is written back
   to any day, and `policy_lop_days` is overwritten wholesale rather than
   incremented. This is the failure mode the brief names — consuming budget
   already consumed — and it is structurally unreachable because there is
   nowhere to consume it *from* except a fresh zero.
3. **Version resolution reads the attendance date, never `now()`.** The only
   input that could change between two runs is the rule table, and §7 restricts
   the one edit that would change an already-priced day.
4. **Rule order is total.** Fixed by enum order within a day, by
   `attendanceDate` within a month, and at most one rule per type per employee
   resolves (§4). There is no set-iteration order anywhere in the path.

**Manual and locked days.** `generateFor` already `continue`s past locked rows
and past `MANUAL` rows without `overwriteManual`, *before* it calls
`computeDay`. Day rules run inside `computeDay`, so they are excluded by the
guard that already exists — no new check, and no second place for the rule to be
missed.

Month rules see **every** stored day, manual and locked included, and I agree
with the brief's position: a day HR corrected to `HALF_DAY` with an 18-minute
early exit is still an 18-minute early exit, and a budget that ignored it would
be measuring the wrong thing. This is safe *because* month rules cannot write to
a day — their only output is a summary-level figure. The asymmetry is not a
special case; it falls out of the two scopes having different write targets.

---

## 7. Payroll impact

A rule outcome reaches money along exactly one path:

```
policyLopDays -> aggregate -> summary.lopDays -> PayrollService.build
   -> payableDays -> earnBasicDA/earnHra/... -> netSalary
                  -> DeductionCalculationService.calculateLopDeduction (shown, not deducted)
```

`OVERTIME` and `DAY_OFF_WORK` reach money by the other existing path:
`summary.overtimeHours` → `PayrollService.overtimeAllowance`.

**Overtime is an AND, not an override.** `OVERTIME`'s `payable:false` zeroes the
*hours*; `Employee.overtimeEligible = false` zeroes the *money*. Both must pass
for overtime to be paid. A rule can only ever reduce what an employee is owed
relative to today, never grant overtime to someone the employee master says is
ineligible.

### `DAY_WISE` employees — a real hole, named

`PayrollService.build` sets `lopDays = ZERO` for `DAY_WISE` and pays
`presentDays` directly. **A month-scoped LOP penalty therefore has no effect
whatsoever on a day-wise employee.** Silently computing a penalty that changes
no rupee is the worst of the options. Proposed: a `MONTH`-scoped rule resolving
for a `DAY_WISE` employee is **refused at write time** if the scope targets them
unambiguously (`EMPLOYMENT_TYPE=DAY_WISE`, or `EMPLOYEE` on a day-wise
employee), and reported in the preview as `notApplicable` for day-wise employees
caught by a broader scope. This is open question **Q3**.

### What happens when HR edits a rule for a finalised month

The brief proposes refusing the recompute and requiring an explicit re-open. I
propose something narrower that removes most of the problem instead of guarding
it.

Because a version is resolved by **attendance date**, adding a version with
`effectiveFrom = 2026-10-01` cannot change any August or September day: replay
resolves the same rows it resolved before and produces the same numbers. The
recompute is safe, so refusing it would be refusing the harmless case. A paid
month can only move if HR creates a version whose `effectiveFrom` falls *inside
or before* an already-priced month — a **back-dated** rule.

So the guard sits on the write, not on the recompute:

> A rule version whose `effectiveFrom` is on or before the latest locked
> `attendance_date` of any employee in its scope is **refused**, with a message
> naming the months affected and the employees in scope. Re-pricing a paid month
> means unlocking it first — the flow Attendance.md already documents for
> corrections: unlock, change, regenerate payroll.

The residual case — a month generated but not yet paid, so not yet locked —
recomputes freely, which is correct: nobody has been paid off it.

For the residual drift that survives all of this, `MonthlyAttendanceSummary`
carries the applied rule versions in `attendance_policy_outcome`, and a
`policyDrifted` flag is computed the way `PayrollDebugRow.ruleDrifted` already
is: compare what was applied against what the live table now resolves. Same
mechanism, same shape, one more column on the debug row.

---

## 8. Worked examples

Throughout: `GENERAL` 09:00–18:00, `workingHours` 8, `breakMinutes` 60,
`graceMinutes` 0, `overtimeWindowMinutes` 240. September 2026, 22 working days,
Sundays off. Employee `SE10012`, `CATEGORY = STAFF`, unless stated.

### A — 15 minutes' grace, then a half day

`LATE_ARRIVAL` @ `CATEGORY=STAFF` v1 from 2026-09-01:
`{graceMinutes: 15, penaltyStatus: HALF_DAY}`

| Date | In | Out | Worked | Late | Base | Final |
|---|---|---|---|---|---|---|
| 1 Sep | 09:14 | 18:05 | 471 min (98%) | 0 | `PRESENT` | `PRESENT` |
| 2 Sep | 09:15 | 18:05 | 470 min | **0** | `PRESENT` | `PRESENT` |
| 3 Sep | 09:16 | 18:30 | 494 min | **1** | `PRESENT` | **`HALF_DAY`** |
| 4 Sep | 09:22 | 18:30 | 488 min | 7 | `PRESENT` | `HALF_DAY` |

2 Sep is the boundary: `lateMinutes = max(0, firstIn − (start + grace))`, so
arriving at exactly 09:15 on a 15-minute grace is **not** late. "Up to 15
minutes" is inclusive, matching the existing formula rather than reinterpreting
it. 3 Sep is one minute past.

```
workingDays 22 | presentDays 21.0 | halfDays 2 | lopDays 1.0
```

Trace on 3 Sep: `HALF_DAY: in 09:16, 1 min beyond a 15 min grace on a 09:00
shift, rule LATE_ARRIVAL v1 scoped CATEGORY=STAFF`.

### B — a 60-minute monthly early-exit budget

`EARLY_EXIT_BUDGET` @ `CATEGORY=STAFF` v1:
`{monthlyBudgetMinutes: 60, penaltyDaysPerOccurrence: 0.5}`

> **ASSUMPTION, needs your ruling — open question Q1.** "Beyond that budget,
> each early exit is deducted" is read as **per occurrence**, half a day each,
> not pro-rata on the minutes. And the day that *exhausts* the budget is
> forgiven — the budget covers it, and penalties start on the next early exit.
> Both readings are defensible; this one gives a crisp boundary and the
> employee the benefit.

Replay in date order:

| Date | Early exit | Consumed before | Result | Consumed after |
|---|---|---|---|---|
| 3 Sep | 20 min | 0 | forgiven (0 < 60) | 20 |
| 9 Sep | 25 min | 20 | forgiven (20 < 60) | 45 |
| 17 Sep | 30 min | 45 | forgiven (45 < 60) — **budget exhausted here** | 75 |
| 24 Sep | 10 min | 75 | **penalised, 0.5 LOP** | 85 |

```
workingDays 22 | presentDays 22.0 | policyLopDays 0.5 | lopDays 0.5
```

Boundary: with 3 Sep 20 min and 9 Sep 40 min, consumption is exactly 60 after
9 Sep, and the *next* early exit is penalised — `consumed >= budget`, not `>`.

Outcome row: `EARLY_EXIT_BUDGET v1 scoped CATEGORY=STAFF: 85 min early exit
across 4 days against a 60 min monthly budget; budget exhausted 17 Sep; 1 later
early exit (24 Sep, 10 min) penalised at 0.5 day = 0.5 LOP days`.

### C — three late marks make a half-day LOP

`LATE_MARK_ACCUMULATION` @ `CATEGORY=STAFF` v1:
`{minimumLateMinutes: 1, occurrencesPerPenalty: 3, penaltyLopDays: 0.5}`

Late on 2, 4, 9, 11, 16, 18, 23 Sep — seven marks.
`floor(7 / 3) = 2`, `2 × 0.5 = 1.0`.

```
workingDays 22 | presentDays 22.0 | lateCount 7 | policyLopDays 1.0 | lopDays 1.0
```

Boundaries: two marks ⇒ 0.0; the third ⇒ 0.5; the sixth ⇒ 1.0.

> **The double-jeopardy guard.** If `LATE_ARRIVAL` (scenario A) is *also*
> configured for this population, 3 Sep is already a `HALF_DAY` for lateness.
> Counting it again as a late mark charges the employee twice for one late
> arrival. **A day whose trace records a `LATE_ARRIVAL` downgrade is excluded
> from the late-mark count.** The rule reads the day's own trace to know. Both
> rules configured for the same population is legal but suspicious, so the
> preview endpoint reports it and validation emits a warning.

### D — workers get 10 minutes, managers are not tracked

| Row | Scope | v | Params |
|---|---|---|---|
| 1 | `CATEGORY=WORKER` | 1 | `enabled: true, {graceMinutes: 10, penaltyStatus: HALF_DAY}` |
| 2 | `CATEGORY=MANAGER` | 1 | **`enabled: false`** |
| 3 | `COMPANY` | 1 | `enabled: true, {graceMinutes: 5, penaltyStatus: HALF_DAY}` |

A worker in at 09:12: late by 2 ⇒ `HALF_DAY`.
A manager in at 09:12: resolution picks row 2 — `CATEGORY` beats `COMPANY` — and
finds it disabled, so **no `LATE_ARRIVAL` applies**. `lateMinutes` is 12 against
the shift's own grace of 0, recorded and consequence-free, exactly as today. The
company rule does **not** apply to the manager: a disabled specific rule is an
answer, not an absence.
A supervisor (neither category): row 3 applies, 5-minute grace.

### E — absolute short-hours thresholds, per category

`SHORT_HOURS` @ `CATEGORY=WORKER` v1:
`{basis: ABSOLUTE_MINUTES, fullDayValue: 240, halfDayValue: 120}`

| Worked | Status | Why |
|---|---|---|
| 245 min | `PRESENT` | ≥ 240 |
| 240 min | `PRESENT` | inclusive, matching today's `>=` |
| 235 min | `HALF_DAY` | ≥ 120, < 240 |
| 120 min | `HALF_DAY` | inclusive |
| 118 min | `ABSENT` | < 120 |

A `STAFF` employee on the same day and the same punches keeps the company's
`AttendanceRule` percentages — 75% of 480 = 360 min full, 40% = 192 min half — so
245 worked minutes is a **half day for staff and a full day for a worker**, and
191 minutes is **absent for staff and still a half day for a worker**. Same
shift, same punches, different populations, no code change.

### F — overtime for workers only, 30-minute blocks

| Row | Scope | Params |
|---|---|---|
| 1 | `COMPANY` | `{payable: false}` |
| 2 | `CATEGORY=WORKER` | `{payable: true, minimumMinutes: 30, roundingBlockMinutes: 30, rounding: DOWN}` |

| Worked | Raw OT | Worker | Everyone else |
|---|---|---|---|
| 8h 20m | 20 min | 0.00 h (below 30) | 0.00 h |
| 8h 35m | 35 min | **0.50 h** (`floor(35/30) = 1`) | 0.00 h |
| 9h 50m | 110 min | **1.50 h** (`floor(110/30) = 3`) | 0.00 h |
| 10h 00m | 120 min | **2.00 h** | 0.00 h |

`otAllowance` still requires `Employee.overtimeEligible` on top (§7). A worker
with `overtimeEligible = false` books 1.50 overtime hours on the day and is paid
nothing for them — the hours are a fact about the day, the allowance is a fact
about the contract.

### G — a worked weekly off is comp-off, not overtime

`DAY_OFF_WORK` @ `COMPANY` v1:
`{onWeeklyOff: COMP_OFF_CREDIT, onHoliday: OVERTIME_PAY, fullCreditMinutes: 240, halfCreditMinutes: 120}`

Sunday 6 Sep, week off, in 09:05 out 18:10: 485 min worked.

- Status stays `PRESENT` — it always was, on a day off.
- `presentDays` is unaffected: 6 Sep is not in `workingDates`, so it never
  entered the sum. Unchanged from today.
- Overtime **zeroed** — today it books **0.08 h**, not 8.08. See the box below.
- Comp-off credit **1.0 day** (485 ≥ 240).

A holiday worked the same way still books overtime, because `onHoliday` says so.

```
weekOffDays 4 | totalHours +8.08 | overtimeHours 0.00 (was 0.08) | compOffCreditDays 1.0
```

> **What working a day off is actually worth today: almost nothing.** Overtime is
> `max(0, workedMinutes - workingHours * 60)` on every day, day off included — so
> a full 8.08-hour shift worked on a Sunday books `485 - 480 = 5` minutes of
> overtime. The day adds nothing to `presentDays` either, because it is not in
> `workingDates`. Attendance.md says the employee "is paid overtime for it, not a
> day's wage"; the arithmetic pays them for five minutes.
>
> This is worth knowing before configuring scenario G, because it changes what
> the choice is between. `COMP_OFF_CREDIT` is not being traded against a day's
> overtime pay — it is being traded against 0.08 h. If a company wants a day off
> genuinely paid as overtime, that is a third treatment, `ALL_HOURS_OVERTIME`,
> which counts every worked minute on a day off as overtime rather than only the
> excess over a shift length nobody was rostered to work. I have **not** included
> it in the catalog above: it is a change to what a day off is worth, not a
> policy scoping question, and it should be an explicit decision rather than
> something smuggled in with this feature. Say the word and it is one more
> enum constant.

> **Where the credit goes is open question Q2.** There is **no `COMP_OFF` leave
> type in this application** — `LeaveType` is a fixed enum of `CASUAL_LEAVE`,
> `SICK_LEAVE`, `LEAVE_WITHOUT_PAY`, each with a hardcoded yearly quota, and
> `LeaveBalance` is keyed on it. The example above records the credit on the
> summary and stops there. Accruing it into a real, bookable balance is a change
> to the leave system, not the attendance one.

### H — a missing out-punch is a half day if the in-punch was on time

`MISSING_PUNCH` @ `COMPANY` v1:
`{fallbackStatus: HALF_DAY, onTimeGraceMinutes: 15}`

| Date | Lone punch | Status | Why |
|---|---|---|---|
| 8 Sep | 08:58 | **`HALF_DAY`** | inside the entry window |
| 9 Sep | 09:15 | **`HALF_DAY`** | exactly at the grace, inclusive |
| 10 Sep | 09:16 | `INVALID_PUNCH` | one minute past |
| 11 Sep | 17:40 | `INVALID_PUNCH` | not an entry — see below |

**`invalidPunch` stays `true` on 8 and 9 September.** The status becomes
`HALF_DAY` so the employee is paid for half the day; the flag stays set so
`invalidPunches` still counts it and HR still sees a device that needs fixing.
This is the same separation `recordStatus` has from `locked`: what the day is
worth, and what went wrong, are two questions.

The 11 September limitation is deliberate. A lone punch at 17:40 is most likely
a missed *entry*, but the device has no in/out flag and nothing distinguishes it
from a late arrival who never left. Guessing there would be inventing evidence;
that is what a mode-1 correction is for.

---

## 9. The API

Base `/api/attendance-policy`. Permissions `ATTENDANCE_POLICY_READ` and
`ATTENDANCE_POLICY_MANAGE`, new codes in `PermissionCode` and `PermissionSeeder`,
granted to HR and ADMIN alongside `ATTENDANCE_RULE_*`. `@PreAuthorize("@authz.can(...)")`
as everywhere else; every mutation through `AuditService`, as
`AttendanceRuleService.updateRule` already does.

| Method | Path | Permission | Does |
|---|---|---|---|
| `GET` | `/rules` | `..._READ` | The caller's company's rules, `?ruleType=&scope=&asOf=` |
| `GET` | `/rules/{id}` | `..._READ` | One version, with its predecessors |
| `POST` | `/rules` | `..._MANAGE` | **Appends a version.** Never edits |
| `DELETE` | `/rules/{id}` | `..._MANAGE` | Only when `effectiveFrom > today` — a future version has priced nothing |
| `GET` | `/effective` | `..._READ` | `?userId=&date=` — the resolved policy for one employee on one date, each type with the scope that won and the ones it beat |
| `POST` | `/preview` | `..._MANAGE` | Re-evaluates a past month under a proposed rule set. Persists nothing |

There is no `PUT`. Changing a rule is appending a version; ending one is
appending a disabled version.

`GET /api/attendance/{userId}/records` gains a `policyApplications` array per
day, and `GET /api/attendance/{userId}/monthly` a `policyOutcomes` array — the
trace surfaces where the numbers it explains already surface, rather than behind
a separate endpoint nobody will find during a dispute.

### Validation errors

`BusinessRuleException` → 400, message naming the field and the bound:

| Refused | Message |
|---|---|
| `graceMinutes` ≥ the shift's span | `graceMinutes 600 exceeds the shortest shift this rule can apply to (GENERAL, 540 min)` |
| `halfDayValue` ≥ `fullDayValue` | `halfDayValue must be less than fullDayValue` — same shape `AttendanceRuleService.validate` already uses |
| Negative budget, minutes, or penalty | `monthlyBudgetMinutes must not be negative` |
| `occurrencesPerPenalty` < 1 | division by zero, refused |
| `fallbackStatus: PRESENT` on `MISSING_PUNCH` | `a single punch cannot earn a full day` |
| `effectiveFrom` at or before a locked date | `2026-08-01 falls inside a locked period (2026-08 is paid for 14 employees) - unlock it first` |
| A second rule of the same type, scope and `effectiveFrom` | 409 from the unique constraint, mapped as the existing handler does |
| `params` that will not parse into the type's record | `params do not match LATE_ARRIVAL: unknown field 'graceMins'` |
| A `MONTH` rule scoped at `DAY_WISE` employees | `month-scoped rules have no effect on DAY_WISE pay` (**pending Q3**) |

---

## 10. Failure modes and guardrails

### The single most damaging misconfiguration

Attendance.md's is `overtime_window_minutes = 0`. Its successor:

> **`LATE_ARRIVAL` with `penaltyStatus: ABSENT` and a small grace, scoped at
> `COMPANY`.** A five-minute grace and an absent penalty turns every employee
> who arrives at 09:06 into a full day of loss of pay — no partial credit, no
> half day, on a day they worked in full. Traffic on one bad morning is a
> company-wide unpaid day.
>
> It is worse than the overtime-window bug in one specific way: that one
> produced `INVALID_PUNCH`, which is visibly wrong and shows up as
> `invalidPunches` in the summary. This one produces `ABSENT`, which is what a
> genuinely absent day looks like, so nothing in the month's figures says
> anything went wrong. The only signal is that `presentDays` fell, and that is
> the number people expect a policy to move.
>
> Run the preview endpoint against last month before you save it. A rule that
> moves more than a few days of LOP across the company is either wrong or a
> decision somebody senior should be making on purpose.

### The preview endpoint

`POST /api/attendance-policy/preview` takes a month and a proposed rule set,
re-evaluates that month for every employee in scope, and returns, per employee:
days whose status changed with before/after and the reason, the LOP delta, the
overtime delta, and a company-level total. It writes nothing — not a day, not a
summary, not a trace row. `AttendanceGenerationRequest.dryRun` already
establishes the pattern and `describeChanges` already builds this shape; the
preview reuses both rather than growing a parallel one.

### Refused outright

- Back-dating into a locked period (§7).
- Two rules of the same type, scope and effective date — by constraint.
- Parameters outside their bounds, at the boundary, on write.
- Deleting anything that has ever been in force.

### Warned, not refused

- `LATE_ARRIVAL` and `LATE_MARK_ACCUMULATION` on the same population — legal,
  and the double-jeopardy guard makes it safe, but rarely what was meant.
- A rule whose scope matches no employee today — a typo'd `scope_ref` is
  otherwise completely silent.
- A preview whose company-wide LOP delta exceeds a threshold.

### Fail closed on a corrupt blob

If a `params` string will not deserialise into its type's record at evaluation
time — a hand-edited row, a rolled-back deploy — generation **refuses** for that
employee, naming the rule id. Skipping the rule silently would change pay by
omission, which is the one outcome that must never happen quietly. The write-time
validation makes this unreachable through the API; it exists for the paths that
do not go through the API.

---

## 11. Test plan and rollout

### The test that is the whole point

`PolicyEngineNoOpRegressionTest` — a company with no configured rules produces
byte-identical output on a fixture month: every day's status, every summary
field, `lopDays`, and `netSalary` off a real payroll run. Asserted against
values captured from the current code before the engine exists. **If this fails,
nothing else about the feature matters.**

It holds structurally, not by luck: with no rows, resolution returns empty for
every type, the day pipeline short-circuits before touching the computed day, no
trace rows are written, and `policyLopDays` is zero. The seeded global scope is
empty (§2), so a fresh install is in this state too.

### The rest

| Suite | Covers |
|---|---|
| `PolicyRuleResolutionTest` | Precedence with employee + category + company all matching; the §4 collision table; disabled-specific-beats-enabled-general; version-in-force-on-date across three versions |
| `PolicyDayRuleTest` | One test class per `RuleType`, at the boundaries: exactly 15 minutes late, exactly 240 worked minutes, exactly 30 overtime minutes, the lone punch at exactly the grace |
| `PolicyMonthRuleTest` | The 60th minute of budget, the 3rd and 6th late mark, the day that exhausts the budget, a month with no qualifying days |
| `PolicyIdempotencyTest` | generate → rebuild → regenerate → rebuild → regenerate one day alone; assert every stored row and every summary field byte-identical, and that trace rows were replaced not duplicated |
| `PolicyNightShiftBoundaryTest` | A night shift starting 30 Sep and ending 1 Oct: its early exit consumes September's budget, not October's — the month a day belongs to is its shift start date, per Attendance.md invariant 10 |
| `PolicyManualAndLockedTest` | A `MANUAL` day is not restatused by regeneration but *does* consume budget; a locked day is skipped entirely; both traces survive |
| `PolicyScenarioEndToEndTest` | One test per worked example A–H in §8, asserting the final salary-affecting numbers — `lopDays`, `payableDays`, `netSalary` — not intermediates |
| `PolicyTenantIsolationHttpTest` | Company B cannot read, edit or delete company A's rules; neither can edit a global row; matching `AttendanceRuleHttpTest` and `TenantIsolationHttpTest` |
| `PolicyPermissionHttpTest` | `ATTENDANCE_POLICY_*` required; an `EMPLOYEE` token is refused; a `SUPERVISOR` token can read but not manage |
| `PolicyValidationHttpTest` | Every row of §9's table returns its documented status and message |
| `PolicyBackdateGuardTest` | A version back-dated into a locked month is refused; the same version dated forward is accepted; unlock-then-retry succeeds |

### Rollout order

Seven commits, each compiling and testable on its own:

1. Schema + entities + repositories + migration SQL. No behaviour.
2. Resolution service + `PolicyRuleResolutionTest`. Still called by nothing.
3. Day-scoped evaluation + the no-op regression test + per-type tests.
4. Month-scoped evaluation + idempotency + the summary columns.
5. The read/write API + permissions + tenant and validation tests.
6. The preview endpoint.
7. Docs: `Attendance.md`, `ARCHITECTURE.md`, `SECURITY.md`, `TESTING.md`, Postman.

Commit 3 is the first that can change a number, and only for a company that has
written a rule row. Commits 1 and 2 are safe to deploy and sit dormant.

---

## 12. What I am deliberately not building

- **A rule that raises a day's value.** No bonus statuses, no "count this as
  present anyway". Only `MISSING_PUNCH` moves a day up, and only from
  `INVALID_PUNCH`.
- **Cross-month state.** No budget carried forward, no rolling 30-day window, no
  quarterly accumulation. Every month starts at zero, which is what makes replay
  a pure fold. A rolling window would need a defined starting point and a
  recompute cascade across months; if it is wanted, it is its own design.
- **Rule composition or inheritance.** Most specific wins whole (§4).
- **A comp-off *balance*.** §8 G records a credit. Accruing and booking it is the
  leave system's job — Q2.
- **`AttendanceRule` migrated into the new model.** It stays. `entryWindowBufferMinutes`
  is consumed by `AttendanceWindowResolver` *before any day exists*, so folding
  it in would force per-employee rule resolution ahead of the punch fetch and
  destroy the per-company batching `resolveCompanyContext` was written for. The
  other two fields become the **base** that `SHORT_HOURS` overrides per
  population — a base and an override, the same relationship `Shift.graceMinutes`
  has to `LATE_ARRIVAL`, not two mechanisms for one job. **The honest cost:**
  "why is this day a half day" now has two places to look. Mitigated by
  `GET /api/attendance-policy/effective`, which returns the resolved policy
  *including* the `AttendanceRule` base it sits on, so there is one endpoint that
  answers the question even though there are two tables behind it.
- **Approval workflow on rule changes.** Audited, not countersigned — matching
  the existing position on corrections.
- **Retrospective recompute of locked months.** Refused at write (§7).

---

## 13. Open questions

**Q1 — answered: per occurrence.** Confirmed. Each early exit after the budget
is exhausted costs a fixed fraction of a day (default 0.5), and the day that
exhausts the budget is forgiven. Implemented and covered by
`MonthPolicyEvaluatorTest.earlyExitBudgetBoundaryIsExact`.

**Q4 — answered by default: yes.** Day rules apply during a mode-1 correction,
as proposed. An explicit `status` in the correction still overrides. Wired in
`AttendanceService.correctDay`.

### Still open

**Q2 — Scenario G: where does a comp-off credit go?** No `COMP_OFF` leave type
exists. (a) Record it on the summary only, HR acts manually — small, no leave
changes. (b) Add `COMP_OFF` to `LeaveType` with a zero default quota and accrue
into `LeaveBalance` — bookable, and touches the leave workflow, quota seeding
and balance tests. (a) is what §8 G describes.

**Q3 — `DAY_WISE` and month-scoped penalties.** They cannot bite (§7). Refuse the
configuration, or convert the penalty into a `presentDays` reduction for
day-wise employees only? A reduction makes it work but muddies "present days",
which the whole system defines as days attended.

---

## 14. Decisions I made

Overrule any of these cheaply.

1. **Succession instead of `effectiveFrom`/`effectiveTo`** (§3). The brief's
   uniqueness constraint is unenforceable on MySQL with date ranges; with
   succession it becomes both enforceable and sufficient.
2. **A narrow back-dating refusal instead of refusing recompute on finalised
   months** (§7). Effective dating already makes the ordinary recompute a no-op;
   only a back-dated version can move a paid month.
3. **`scope_ref NOT NULL` with a `'*'` sentinel**, because both databases treat
   `NULL`s in a unique index as distinct.
4. **Codes, not ids**, for the category/department/designation scope reference —
   they are per-company unique since Phase 6 and they read correctly in an
   explanation.
5. **`enabled = false` is the only opt-out.** No second "suppress tracking"
   parameter.
6. **The global scope ships empty.** No seeded rules of any kind.
7. **Day rules live inside `calculateDay`, month rules inside `aggregate`** —
   not in `AttendanceService.generateFor` and `rebuildSummary` — so corrections
   and the read path cannot drift from generation.
8. **`invalidPunch` stays `true` when `MISSING_PUNCH` rescues the day** (§8 H).
9. **Overtime eligibility is an AND** of the rule and `Employee.overtimeEligible`
   (§7).
10. **`lopDays` is clamped to `workingDays`** after the policy delta (§5).
11. **Days already downgraded by `LATE_ARRIVAL` are excluded from the late-mark
    count** (§8 C) — no double jeopardy.
12. **`MISSING_PUNCH` may not award `PRESENT`** (§9).
13. **A corrupt `params` blob fails generation rather than skipping the rule**
    (§10).
14. **No `PUT` and almost no `DELETE`** on rules (§9) — append-only, matching
    `SalaryRevision`.

---

## 15. Where the implementation deviated from this design

Recorded rather than quietly folded in, so the diff can be reviewed against the
document that was approved.

1. **Month-scoped rules resolve on the FIRST of the month, not per day.** Not
   specified above. A budget or an occurrence counter is a property of the month
   as a whole, so it needs one version for the whole month. Resolving on the
   month's *end* would let a rule created on the 28th retroactively re-judge the
   preceding 27 days — the exact surprise effective dating exists to prevent.
   Consequence to know about: a month-scoped rule with a mid-month
   `effectiveFrom` governs from the *following* month. Documented in
   `AttendanceService.monthPolicyFor` and in the Postman collection.

2. **`ATTENDANCE_POLICY_READ` is not granted to `SUPERVISOR`.** §9 said a
   supervisor could read. `ATTENDANCE_RULE_READ` is HR/ADMIN-only today, and
   consistency with the permission this one sits beside beat the design's
   original guess.

3. **`MISSING_PUNCH` awards the status but not the hours.** Not specified. A
   rescued day gets `HALF_DAY` (so the employee is paid for half of it) while
   `workingHours` stays zero. There is no evidence of hours worked — the missing
   out-punch is the entire premise — and `totalHours` drives `DAY_WISE`
   overtime, so crediting hours here would invent overtime pay out of a device
   fault. The status is a policy decision about what the day is worth; the hours
   are a factual record of what was observed.

4. **`AttendanceStatus` gained `dayFraction()`.** The engine needs the same
   figures to enforce "a rule may only lower a day's value", and two copies of
   "a half day is 0.5" on a path that decides pay is not something to have.
   `AttendanceCalculationService.dayFraction` delegates to it and stays the
   entry point every existing caller uses.

5. **The preview evaluates stored days rather than regenerating from punches.**
   The question HR is asking is what this rule set would do to *the month they
   are looking at*, manual corrections included — not what a fresh generation
   would produce.

6. **Two guardrails added that the design did not list.** `assertScopeRefExists`
   refuses a scope reference matching no employee (otherwise a typo is
   completely silent), and the preview warns when a rule set changes nothing.

### Bugs found while building it, worth remembering

- **`"a" + "b".formatted(args)`** binds `.formatted` to the second literal only
  and silently misaligns every argument. It produced a trace sentence with the
  wrong values in it — the one thing the trace exists to get right.
- **`@Builder` ignores field initialisers without `@Builder.Default`.** The two
  new `NOT NULL` summary columns went in as `NULL` and broke 61 tests. This is
  the same failure the migration file warns about for existing databases, hit in
  the test suite first, which is the better order.
- **`attendance_policy_rule` carries the only FK from the attendance tables to
  `company`.** Rules left behind by one suite broke the *next* suite's
  `companyRepository.deleteAll()`, in its `setUp`, where the cause is least
  obvious.
