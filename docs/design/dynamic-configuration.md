# Making the hardcoded rules configurable — design amendment

An amendment to [attendance-policy-engine.md](attendance-policy-engine.md),
covering the wider ask: employment-type behaviour and leave types as data
rather than as Java enums.

Read that document first. This one does not replace it — it explains why the
same argument applies twice more, and where I would stop.

**Status: section C (employment types) is built.** Section B (dynamic leave
types, and with it a bookable comp-off balance) is not. See ARCHITECTURE.md's
"Employment types are configurable" and
`docs/migrations/2026-09-02-employment-types.sql`.

---

## 1. The finding that reframes this

**The rule you described already exists, and it is already hardcoded.**

`PayrollService.monthlyOvertimeHours` (line 603):

```java
BigDecimal baseHours = presentDays.min(BigDecimal.valueOf(rule.getDayWiseDaysInMonth()))
        .multiply(rule.getStandardHoursPerDay());
BigDecimal workedOvertime = totalHours.subtract(baseHours).max(BigDecimal.ZERO);
BigDecimal leaveOvertime  = paidLeaveDays.multiply(rule.getStandardHoursPerDay());
return workedOvertime.add(leaveOvertime);
```

That *is* "a day-wise employee on leave, capped at 26 days, and the leave goes
into overtime." Its own Javadoc says so:

> Uncapped and additive is the only form that reliably behaves like "if you have
> approved leave, it goes into the OT hours" for every attendance mix.

So the thing to build is not a way to invent new rules. It is a way to turn the
rules **already baked into `PayrollService`** into rows a client can edit. That
is a much smaller, much safer, and much more valuable piece of work than "let
the user add anything", and it is what your second client actually needs.

### Everything one hardcoded boolean decides

`boolean dayWise = employee.getStatus().isPaidPerAttendedDay();` — one line,
`PayrollService.build:348` — then branches on it **seven times**:

| # | Line | Decides | `DAY_WISE` | Everyone else |
|---|---|---|---|---|
| 1 | 354 | Proration base | `dayWiseDaysInMonth` (26) | calendar days in month |
| 2 | 364 | Whether LOP applies | forced to `ZERO` | `summary.lopDays` |
| 3 | 372 | How payable days derive | `min(presentDays, 26)` | `workingDays − lopDays`, capped by employed window |
| 4 | 373 | Whether paid leave adds to payable days | **no** | yes, via LOP offset |
| 5 | 398 | Stored `presentDays` | capped at 26 | raw |
| 6 | 408 | Overtime basis | `monthlyOvertimeHours` (monthly total) | `summary.overtimeHours` (per-day sum) |
| 7 | 419 | Segmented mid-month revision earnings | skipped | applied |

Plus, outside payroll: `DefaultRosterService:49` auto-rosters `PERMANENT` only,
and `PayrollAuditService`, `PayrollRegisterService` and
`AttendanceLeaveReportService` each re-derive the same boolean to format
reports.

Seven payroll behaviours hang off one enum constant that no client can change.
That is the real problem, and you identified it correctly.

---

## 2. Where I agree, and where I would stop

You said you want *everything* dynamic. I want to split that into two things,
because one of them is straightforwardly right and the other is the thing your
own brief told me not to build — correctly.

**Dynamic data — unlimited, yes.** How many employment types exist, what they
are called, what numbers they carry, which populations get which attendance
rule, how many leave types there are, what each accrues. A client should add
these through the API without anyone writing Java. There is no reason for a
limit and no risk in removing it.

**Dynamic behaviour — a fixed vocabulary, composed freely.** A *kind* of
calculation — "what does it even mean to pay this person" — is a different
thing. If a client can define a new kind, you need an expression evaluator in a
database column, and you already ruled that out for reasons I agree with: it
decides salary, it cannot be tested, it cannot be migrated, and it cannot be
explained to an employee disputing a deduction.

The bridge between the two is that **the vocabulary is tiny and the
compositions are unlimited.** There are two ways to pay somebody in this
system — per attended day, or per calendar day less loss of pay. Not two
hundred. A client does not need to invent a third; they need to make as many
employment types as they like, each picking one of the two and carrying its own
numbers. That is genuinely dynamic from where the client sits, and it is still
arithmetic you can test and I can explain.

Concretely, the line I would draw:

| Client can do, no code | Needs a code change |
|---|---|
| Add `CONTRACT_SITE`, `RETAINER`, `APPRENTICE` — any number | Add a third `PayBasis` |
| Set any of them to per-attended-day with a 24-day cap | Invent a pay basis that is neither |
| Turn "paid leave earns overtime" on for one, off for another | Add a new *kind* of overtime |
| Add `COMP_OFF`, `MATERNITY`, `BEREAVEMENT` leave — any number | Add a new accrual *mechanism* |
| Attendance rules per category/department/employee | A new `RuleType` |

Everything on the left is a form. Everything on the right is a pull request.
I would expect the right-hand column to be touched perhaps twice a year, and the
left-hand one daily. If it turns out you are hitting the right-hand column
weekly, the vocabulary is wrong and we should widen it — but widen it with named
constants, not with a formula column.

---

## 3. `employment_type` — the table that replaces the enum

```sql
CREATE TABLE employment_type (
    id                          BIGINT      NOT NULL AUTO_INCREMENT,
    company_id                  BIGINT      NULL,        -- NULL = shared catalog, as Shift/Category
    type_code                   VARCHAR(30) NOT NULL,
    type_name                   VARCHAR(60) NOT NULL,

    -- how pay is derived
    pay_basis                   VARCHAR(30) NOT NULL,    -- PER_ATTENDED_DAY | PER_CALENDAR_DAY_LESS_LOP
    payable_days_cap            INT         NULL,        -- 26 for day-wise; NULL = no cap
    paid_leave_adds_payable_days BIT(1)     NOT NULL,
    lop_applies                 BIT(1)      NOT NULL,

    -- how overtime is derived
    overtime_basis              VARCHAR(30) NOT NULL,    -- PER_DAY_SHIFT_EXCESS | MONTHLY_TOTAL_HOURS
    paid_leave_earns_overtime   BIT(1)      NOT NULL,

    -- the rest
    segmented_revision_earnings BIT(1)      NOT NULL,
    auto_roster_default_shift   BIT(1)      NOT NULL,
    active                      BIT(1)      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_employment_type_company_code UNIQUE (company_id, type_code)
);
```

Same per-company-plus-shared-catalog shape as `Shift`, `Category`,
`Department` and `Designation` — the pattern SECURITY.md Phase 6 established,
so tenant isolation, the read/write asymmetry on shared rows, and the
`getByCode(companyId, ...)` fallback all come for free rather than being
designed again.

### Your rule, as data

The four seeded rows reproduce today's enum exactly:

| `type_code` | `pay_basis` | cap | `lop_applies` | `paid_leave_earns_overtime` | `overtime_basis` |
|---|---|---|---|---|---|
| `PERMANENT` | `PER_CALENDAR_DAY_LESS_LOP` | — | true | false | `PER_DAY_SHIFT_EXCESS` |
| `DAY_WISE` | `PER_ATTENDED_DAY` | **26** | false | **true** | **`MONTHLY_TOTAL_HOURS`** |
| `CONTRACT` | `PER_CALENDAR_DAY_LESS_LOP` | — | true | false | `PER_DAY_SHIFT_EXCESS` |
| `INTERN` | `PER_CALENDAR_DAY_LESS_LOP` | — | true | false | `PER_DAY_SHIFT_EXCESS` |

The `DAY_WISE` row is your rule: per attended day, capped at 26, and paid leave
earns overtime hours. It is not written in Java any more; it is a row your
client can edit. Client 2 sets the cap to 24, or turns
`paid_leave_earns_overtime` off, or creates `DAY_WISE_SITE` with different
numbers and leaves yours alone — none of it a deploy.

The `payable_days_cap` also stops being tied to `SalaryRule.dayWiseDaysInMonth`,
which is currently one number for the whole company and therefore cannot differ
between two day-wise populations.

### The seven branches become field reads

```java
// before
BigDecimal totalDays = dayWise
        ? BigDecimal.valueOf(rule.getDayWiseDaysInMonth())
        : BigDecimal.valueOf(period.lengthOfMonth());

// after
BigDecimal totalDays = type.getPayBasis() == PayBasis.PER_ATTENDED_DAY
        ? BigDecimal.valueOf(type.getPayableDaysCap())
        : BigDecimal.valueOf(period.lengthOfMonth());
```

Same arithmetic, same rounding, same `BigDecimal`. The `if` moves from the enum
to the row. That is the whole change, repeated seven times.

### `Payroll` must snapshot the behaviour, not just the name

`Payroll.employmentStatus` currently stores the enum. A dynamic type means the
row can be edited after a payslip is printed, so `Payroll` snapshots the
**behaviour that was in force** — `employment_type_code`, `pay_basis`,
`payable_days_cap`, `paid_leave_earns_overtime` — exactly as `snapshotRule`
already does for `SalaryRule`'s percentages. Without that, reprinting last
March's slip after someone edits the type gives a different number, and
ARCHITECTURE.md's "immutable payroll history" guarantee is gone.

This is the single most important detail in this document.

### Blast radius, honestly

`EmployeeStatus` is referenced in **20 places**: `Employee`, `Payroll`, three
report services, `DefaultRosterService`, `EmployeeCsvParser`,
`EmployeeRepository.findByRecordStatusAndStatus`, six DTOs and the CSV parser.
Most are pass-through (`EmployeeResponse`, report DTOs) and become a `String`
code. Three carry real logic and need care: `PayrollService.build`,
`PayrollAuditService`, `DefaultRosterService`.

The enum does **not** have to disappear on day one. `Employee` gains an
`employment_type_id` alongside the existing `status` column, backfilled by code;
`status` stays as a read-only shadow until every consumer is migrated, then
drops in a later migration. That keeps each commit revertible, which on a
payroll path matters more than tidiness.

---

## 4. `leave_type` — and comp-off

Same problem, same shape. `LeaveType` is a fixed enum of three, each with a
hardcoded yearly quota, and `LeaveBalance` keys on it. This is why comp-off —
the thing your second client actually asked for — is impossible today.

```sql
CREATE TABLE leave_type (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    company_id            BIGINT      NULL,
    type_code             VARCHAR(30) NOT NULL,
    type_name             VARCHAR(60) NOT NULL,
    paid                  BIT(1)      NOT NULL,   -- offsets LOP?
    default_yearly_quota  DECIMAL(5,1) NOT NULL,
    accrual               VARCHAR(30) NOT NULL,   -- FIXED_YEARLY_QUOTA | EARNED_FROM_ATTENDANCE
    consumes_balance      BIT(1)      NOT NULL,
    active                BIT(1)      NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_leave_type_company_code UNIQUE (company_id, type_code)
);
```

`accrual = EARNED_FROM_ATTENDANCE` is what makes comp-off work, and it is the
one place these two designs meet: the `DAY_OFF_WORK` rule from §8 G of the
policy-engine design emits a comp-off credit, and a leave type with this accrual
mode is where that credit lands as bookable balance.

That closes **open question Q2** from the first document. It also closes **Q3**:
once `lop_applies` is a field rather than a hardcoded `dayWise` branch, a
month-scoped attendance penalty applies to whichever types say it should, and
the "day-wise employees cannot be penalised" hole disappears instead of needing
a special case.

Your instinct did not add scope to my design. It removed two of its four open
questions.

---

## 5. What stays fixed, and why that is fine

Three small enums stay enums, and I want to be explicit that this is a
recommendation you can overrule:

- **`PayBasis`** — two values. A third would be a genuinely new theory of pay.
- **`OvertimeBasis`** — two values, matching the two that exist in the code now.
- **`RuleType`** — the attendance catalog from the first document.

Each is a named constant with tested arithmetic behind it. A client composing
freely from them can express every policy in your brief and everything your
second client has asked for. If a third client turns up with something none of
them cover, that is a pull request and a migration, and the honest answer is
that it *should* be — a new way of computing somebody's salary deserves a code
review, not a form.

---

## 6. Sequencing

Three pieces of work. Doing them at once would put a schema change, a payroll
refactor and a new engine into one untestable diff, and `PayrollService.build`
is the most dangerous method in this application.

| | Work | Delivers | Risk | Touches payroll? |
|---|---|---|---|---|
| **A** | Attendance policy engine (first document) | Per-population attendance rules | Low — purely additive, no rows means no change | No |
| **B** | Dynamic leave types | **Comp-off**, any leave type a client wants | Medium — `LeaveBalance` FK, quota seeding | Indirectly, via paid-leave offset |
| **C** | Dynamic employment types | Your day-wise/26/overtime rule as data | **High** — seven payroll branches | Yes, directly |

For your second client, **A + B is the whole ask**: attendance rules per
population, and comp-off end to end. C is the architectural fix you want, and
it is the one that can quietly change what somebody is paid, so it wants the
other two landed and proven first — and it wants its own byte-identical
regression test against the four seeded types before it goes anywhere near a
client.

Recommended order: **A → B → C.** Every one of them is additive, and each ships
with the same guarantee as the first document — a company that configures
nothing produces byte-identical output.
