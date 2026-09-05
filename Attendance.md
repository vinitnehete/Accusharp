# Attendance

Everything about how a biometric punch becomes a day of pay. This is the core of
the system: payroll, LOP, salary slips and every report are arithmetic on top of
what is decided here. If attendance is right, the rest follows.

For the wider system see [ARCHITECTURE.md](ARCHITECTURE.md); for how to run it,
[README.md](README.md).

---

## The model in one line

```
raw punches + roster  ->  one day  ->  a month  ->  reviewed & corrected  ->  locked & paid
```

Two ideas carry the whole design:

1. **The roster is the source of expectation.** A day the employee was
   scheduled on is interpreted through that shift - its window, grace, break
   and length - and this is why *absent* can mean "expected to work and did
   not" rather than merely "no punches".

   A day with **no roster row at all** is still an attendance day, but a blank
   one: `shift_code` null, no punch times, no hours, status `ABSENT`. A missing
   roster row is an HR oversight, not a statement that nothing was expected -
   and treating it as one produced a blank month HR could not review and a
   payroll that saw zero working days and paid it in full. See section 3.1.
2. **Attendance is a stored artifact, not a live view.** It is generated once,
   reviewed by a human, corrected where the device got it wrong, then frozen.
   Payroll reads the frozen record, never the raw punches.

---

## 1. The inputs

| Input | Table | Who writes it | Role |
|---|---|---|---|
| Raw punches | `device_logs` | The eSSL device's middleware, directly | What actually happened |
| Roster | `emp_attendance_shift` | HR / supervisors, via the API, or `DefaultRosterService` for PERMANENT employees | What was expected |
| Shift master | `shift` | HR, via the API | How to interpret the punches |
| Holiday calendar | `holiday` | HR, via the API | Which days are not working days |
| Approved leave | `leave_request` | The leave workflow | Why an absence is excused |

**PERMANENT employees roster themselves onto `GENERAL` automatically.**
`DefaultRosterService` tops up every active PERMANENT employee's roster to two
months ahead - once at creation (or whenever an update makes an employee
PERMANENT again), and monthly after that (`@Scheduled`, the 1st of the month).
It only ever fills gaps: a day HR already assigned, edited, swapped or holiday
-overrode is never touched, so `ShiftScheduleController`'s single-day
assign/override still works exactly as before. Sunday is the only day written
as a week off; the holiday calendar still applies on top, independently, the
same as any other roster row. If the `GENERAL` shift is missing for a company
(an unseeded environment, or a renamed/removed code), the employee is simply
skipped with a warning - this default is a convenience, never a precondition
for creating or updating an employee. Every other employment type is
unaffected and still requires an explicit assignment.

**Shifts and holidays are per-company.** `Shift` carries a nullable `company`
- `company = null` rows are a shared, read-only-to-companies catalog (the
four seeded standard shifts); a company's own HR can add custom shifts on top
but can never edit or delete the shared ones (see SECURITY.md Phase 6).
Holidays have no shared/global concept - every holiday belongs to exactly one
company. `HolidayService.mandatoryHolidayDates` takes an explicit
`companyId` and is threaded through every attendance/roster call site that
needs it; an employee with **no company of their own** (`companyId == null`,
an edge case, not the normal path) falls back to every company's holidays
merged together - the pre-fix behavior for a real gap once found here (two
companies' calendars bleeding into each other's attendance), see
SECURITY_AUDIT.md finding 3.

`device_logs` is **read-only to this application.** There is no endpoint to
create or edit a punch, and there never will be - corrections are recorded
against the generated attendance day instead, so the original device reading
survives next to the correction and a device re-sync cannot clobber it.

A punch row is just `(user_id, log_date)`. There is **no in/out flag** - the
device only records that somebody badged. Whether a punch is an entry or an exit
is inferred, which is the root of most of the subtlety below.

`user_id` is the join key across everything. It must match the device's user id
exactly or the employee simply has no attendance.

---

## 2. Stage one: the punch window

For each rostered day the engine works out the span of time in which a punch
counts towards that day.

```
        windowStart                              windowEnd
             |                                        |
   ----------[========== shift ==========]------------)------------>
             |          |            |                |
    start - 60min    start          end        end + overtimeWindowMinutes
```

- **Opens `entryWindowBufferMinutes` before the start.** People badge in early.
  Configurable per company on `attendance_rule`, default 60.
- **Closes `overtimeWindowMinutes` after the scheduled end.** Configurable per
  shift, default 240 (4 hours).

Both edges are *soft*. The span between the shift's own start and its scheduled
end is not - see "A shift owns its own span" below, which is what decides any
punch two days both want.

The asymmetry is deliberate. A symmetric buffer would make a long overtime day
look like a missing exit punch, so the closing side is sized for real overtime.

> ### The single most damaging misconfiguration
>
> **`overtime_window_minutes = 0` silently destroys attendance.** The window then
> closes at the exact scheduled end, so every exit punched even one minute late
> falls outside it. The day is left holding one punch, is classified
> `INVALID_PUNCH`, and becomes loss of pay.
>
> This is not a rare edge case - on a shift ending at 19:00, everyone who leaves
> at 19:05 loses the day. See the worked example in section 9.
>
> `grace_minutes` does **not** help here. Grace only decides whether an entry is
> *late*; it has no effect whatsoever on which punches are collected.

### Night shifts

A shift **crosses midnight** when its end time is not after its start time:

```java
endTime.isAfter(startTime) == false     // e.g. 18:00 -> 08:00
```

Its window therefore closes on the **following calendar day**, but the day still
belongs to the shift's **start** date. A night shift begun on 30 June is a June
day even though the employee punches out on 1 July - including when that crosses
a month boundary. This is handled in one place (`AttendanceCalculationService`)
so no caller has to think about midnight.

### A shift owns its own span

A night shift's window crosses midnight and, with an overtime window on top,
can reach into the hours the *next* scheduled day is also collecting for:

```
15 Jun NIGHT  18:00-08:00 (+240)   window ...............-> 16 Jun 12:00
16 Jun GENERAL 09:00-19:00         window   16 Jun 08:00 ->
                                            |__overlap__|
```

Each rostered day therefore claims two spans, and the difference between them
decides every contested punch:

```
     soft head          CORE (contractual)          soft tail
    [-----------|===============================|-----------------)
  start-buffer   shiftStart          scheduledEnd   +overtimeWindow
```

The **core** is the shift the employee was actually rostered to work. It
crosses midnight for a night shift, and it is **inviolable** - no adjacent
assignment may take a punch out of it. The soft head (people badge in early)
and the soft tail (people work over) are conveniences, and they yield.

Punches are then **partitioned** across days in one pass, so a punch belongs to
exactly one day by construction rather than by whether two ranges happened to
overlap. Where two days both claim a punch:

1. a day with **independent evidence** of having been worked - at least one
   punch no other day can claim - beats a day with none. Applied only when it
   discriminates: if both have their own evidence, or neither does, it decides
   nothing and the rules below take over;
2. a day whose **core** contains it beats a day that only claims it softly;
3. if **both cores** contain it the roster is physically unworkable, so the
   **earlier shift date** wins and both days are flagged as a roster conflict;
4. if **neither core** does, it goes to whichever core is nearer - an overtime
   punch stays with the shift that ran over, an early arrival goes to the shift
   about to start.

Rule 1 is there because rule 3 alone gets a common case badly wrong. On an
unworkable `NIGHT -> MORNING` pair a 07:18 punch is inside both cores. If the
night was simply not worked - no evening punch anywhere - awarding 07:18 to it
on seniority of date invents a night shift nobody worked *and* strands the
morning holding only its own exit punch, so both days read `INVALID_PUNCH` and
both become loss of pay. Asking first whether either day holds a punch that is
unambiguously its own settles it on evidence rather than calendar order. This
is not inference from punch clustering: a day either has a punch no other day
can claim, or it does not.

A weekly off never defends a core: nobody was expected to work it, so it cannot
take the previous night's exit punch. It keeps its soft window, because someone
who does turn up on their day off is still `PRESENT`.

> ### The bug this replaced
>
> Until this design, a day's window was simply truncated where the *next*
> rostered day's window opened - `min(rawEnd, nextWindowStart)`, with no floor.
> The next day's sixty-minute entry buffer therefore outranked the current
> shift's own end time. A `NIGHT` shift followed by a `GENERAL` day had its
> window cut to 08:00 *exclusive*, so an employee punching out at exactly 08:00
> - the scheduled end, the most likely minute of all - had the exit discarded,
> was left holding one punch, and lost the day to `INVALID_PUNCH` and a day's
> pay. The next day inherited the orphaned punch and lost its day too. Punching
> out at 07:59 was a full present day; 08:00 cost two.
>
> It needed no unusual data. The seeded shifts did it, `auto-rotate` produced
> the roster on every rotation boundary, and `DefaultRosterService`'s Sunday
> week-off rows did it to every Saturday night shift.

> **Still not validated:** the roster accepts a night shift ending 08:00
> followed by a morning shift starting 06:00, which nobody can physically work.
> The engine now resolves it deterministically instead of destroying both days,
> and `SHIFT_SCHEDULE_REST_GAP` is logged and audited when such a pair is
> assigned - but it is a warning, not a rejection.

## 3. Stage two: calculating one day

Given the punches inside the window, ascending by time.

### Fewer than two punches

| Punches | Then |
|---|---|
| Exactly 1 | `INVALID_PUNCH` — a device or user error, **never** an absence |
| 0, on approved leave | `ON_LEAVE` |
| 0, mandatory holiday | `HOLIDAY` |
| 0, weekly off | `WEEKLY_OFF` |
| 0, otherwise | `ABSENT` |

The order matters: leave beats holiday beats weekly off. A lone punch outranks
all of them, because it is evidence the person was there and evidence something
went wrong - it must be surfaced for correction, not quietly written off.

### Two or more punches

```
firstIn       = first punch
lastOut       = last punch
spanMinutes   = lastOut - firstIn
breakMinutes  = see below
workedMinutes = max(0, spanMinutes - breakMinutes)

lateMinutes      = max(0, firstIn - (shiftStart + graceMinutes))
earlyExitMinutes = max(0, scheduledEnd - lastOut)
overtimeMinutes  = max(0, workedMinutes - workingHours * 60)
```

**Only the first and last punch decide the day.** Anything punched in between
is kept in `device_logs` but has no effect on hours, break, overtime or status.
The unpaid break is always the shift's configured `breakMinutes`.

> An earlier version tried to be cleverer: with four or more punches and an even
> count it treated the middle pairs as real out-and-back-in cycles and measured
> the break between them. The device makes that unsafe - a punch row is just
> `(user_id, log_date)` with no in/out flag, so nothing distinguishes a genuine
> mid-shift exit from the reader firing twice on one badge. It fired twice
> routinely, seconds apart, and the cost was severe: punches at `10:25:42`,
> `10:25:44`, `20:40:13`, `20:40:14` read as one minute of work, a ten-hour
> break, and one more minute - `614 - 614 = 0` worked minutes, so a full day
> plus two hours of overtime scored `ABSENT` and became loss of pay.
>
> The trade is deliberate: someone who genuinely leaves mid-shift for three
> hours is now paid as though they took only the configured break. That is a
> correction (section 6), not a calculation.

**Day value.** Measured as a share of the shift's paid hours:

| Worked | Status | Worth |
|---|---|---|
| ≥ 75% of shift | `PRESENT` | 1.0 day |
| ≥ 40% of shift | `HALF_DAY` | 0.5 day |
| below 40% | `ABSENT` | 0 |

**Worked on a day off.** If the day is a weekly off or a holiday and the person
turned up, the status is `PRESENT` regardless of hours. But note the
consequence in section 4: those hours count towards total and overtime hours,
while the day itself does **not** add to `presentDays`, because it was never an
expected working day. The employee is paid overtime for it, not a day's wage.

### 3.1 A day with no shift

Everything above needs a shift: the window, the grace period, the break, the
paid length a day is scored against. A day with no roster row has none of them,
so nothing can be *calculated* for it - but it still becomes a row:

| Field | Value |
|---|---|
| `shift_code` | null |
| `first_in` / `last_out` | the day's punches if any, otherwise null |
| `working_hours`, `break_hours`, `overtime_hours` | 0 |
| `late_minutes`, `early_exit_minutes` | 0 |
| `invalid_punch` | false - the device worked, the roster is what is missing |
| `week_off` | false - nothing said this day was off |
| `holiday` | from the company calendar, same as any other day |
| `status` | `ON_LEAVE` if on approved leave, else `HOLIDAY` if a mandatory holiday, else `ABSENT` |

Three things follow, and each is deliberate:

- **It is a working day, so it is loss of pay.** That is the point. Before this
  existed an employee nobody rostered generated no rows at all: HR opened the
  month and saw a blank sheet with nothing to review, and payroll - which
  derives LOP from `workingDays - presentDays - paidLeave` - saw zero working
  days and paid the month in full. The gap now shows up in the figure HR
  reviews instead of being invisible in it.
- **A holiday and an approved leave still read as themselves.** Neither is a
  working day, so invariant 4 holds through this path too: a missing roster row
  can never turn a holiday into LOP.
- **The employment window bounds it.** Nothing is written before `joiningDate`
  or after `relievingDate`. Nobody is absent before they were hired.

**Punches are kept, not scored.** Someone who badged in on a day HR forgot to
schedule keeps their `first_in` and `last_out` on the row, and the day is still
`ABSENT`: with no shift there is no threshold that could call it present, and
inventing hours from a missing roster row would be a guess that changes pay.
The times are the evidence that says *the roster* is what needs fixing.

**Turning it off.** `includeUnrostered: false` on the generate request restores
the older behaviour, where an unrostered day is not an attendance day at all.
That is the right setting for a company that rosters deliberately sparsely -
casual or contract staff scheduled only on the days they actually work - where
an unrostered day genuinely means "not a working day". Note that such staff are
usually paid per attended day anyway (`lopApplies() == false`), for whom the
filled days add visibility without changing pay.

**Correcting one.** The row is correctable like any other, with one restriction:
because the recompute path needs a shift, an unrostered day can only be fixed by
forcing a status (`{"status": "PRESENT", "remarks": "...", "updatedBy": "..."}`).
Supplying punch times alone is refused with a message that says to assign the
shift and regenerate. Supplied times are still stored alongside a forced status.

The real fix is almost always the roster, not the day: assign the shift and
regenerate, and the whole month recomputes properly.

**The preview does not do this.** `GET /{userId}/monthly` on a month nobody has
generated yet returns a preview built from the roster (section 5), and an
unrostered month previews as empty rather than as thirty absences. That is
deliberate: a preview persists nothing and is often read for a month that has
not happened yet, where presenting a wall of `ABSENT` would be alarming and
wrong. Generation is the step that decides a day exists.

---

## 4. Stage three: rolling up a month

**Working days** - the denominator everything else hangs off:

```
workingDays = rostered days that are neither a weekly off nor a mandatory holiday
```

Optional holidays stay working days. Days with no roster row are counted, as
blank `ABSENT` days (section 3.1) - unless the run passed
`includeUnrostered: false`, in which case they are not counted at all and
skipping a holiday during bulk assignment removes it from the month entirely.

```
presentDays  = sum of day values, counting only days in workingDays
leaveDays    = approved leave fractions, counting only days in workingDays
paidLeaveDays= as above, but only leave types that are paid
absentDays   = max(0, workingDays - presentDays - leaveDays)
lopDays      = max(0, workingDays - presentDays - paidLeaveDays)
```

**LOP is never entered by hand.** Because working days already exclude weekly
offs and mandatory holidays, neither can ever become loss of pay. Unpaid leave
(`LEAVE_WITHOUT_PAY`) counts in `leaveDays` but not `paidLeaveDays`, so it
correctly *does* become LOP.

Leave taken on a weekly off or holiday is ignored - it is not consumed and must
never offset LOP.

Also reported: `halfDays`, `holidayDays`, `weekOffDays`, `lateCount` (days with
any late minutes), `earlyExitCount`, `invalidPunches`, `totalHours`,
`overtimeHours`.

`holidayDays` and `weekOffDays` come from flags snapshotted on each stored day,
not from the status - because someone who works a holiday reads as `PRESENT`,
and the day is still a holiday.

**Rounding.** Hours to 2 decimal places, days to 1, always `HALF_UP`. All money
and day arithmetic is `BigDecimal`; there is no floating point anywhere in the
calculation path.

---

## 5. Stage four: generate, review, correct, lock

```
   generate  ->  review  ->  correct  ->  payroll  ->  locked
  (GENERATED)              (MANUAL)                  (frozen)
```

Attendance rows live in `emp_daily_attendance`, one per employee per rostered
day, unique on `(user_id, attendance_date)`.

### Two independent status fields

| Field | Values | Means |
|---|---|---|
| `status` | `PRESENT` `HALF_DAY` `ABSENT` `ON_LEAVE` `WEEKLY_OFF` `HOLIDAY` `INVALID_PUNCH` | What the day **is** |
| `recordStatus` | `GENERATED` `MANUAL` | Where the row **came from** |
| `locked` | boolean | Whether payroll has **frozen** it |

They are deliberately separate. Locking must not erase the fact that a human
corrected a day - that is exactly the question asked when a salary is disputed
months later. A UI badge is `locked ? "LOCKED" : recordStatus`.

### Generate

```
POST /api/attendance/generate
{ "month": "2026-06", "userIds": ["SE10012"], "overwriteManual": false,
  "includeUnrostered": true }
```

`includeUnrostered` defaults to `true` - see section 3.1.

`generatedBy` is not a request field to set - it is always the authenticated
caller (from the bearer token), never client-supplied. See [SECURITY.md](SECURITY.md)
for the full auth model; every endpoint on this page requires it.

Omit `userIds` to run the whole company - "whole company" here means the
*caller's own* company specifically, scoped via `TenantContext`, not every
employee on the platform.

**Safe to rerun.** This is how late-arriving or corrected device data gets picked
up. Two rules govern a rerun:

| Existing row | Rerun does |
|---|---|
| `GENERATED` | Recomputes it from punches |
| `MANUAL` | **Preserves it**, counted in `manualPreserved` |
| `locked` | **Skips it**, counted in `lockedSkipped` |

Both rules apply to unrostered days too: a blank `ABSENT` day someone corrected
to `PRESENT` survives the next run exactly like any other `MANUAL` row.

The response reports `unrosteredDaysGenerated` - the subset of `daysGenerated`
that had no shift behind them - separately from the total, and
`employeesWithoutRoster` still lists everyone with no roster at all for the
period. A non-zero count on either means the roster, not the attendance, is
what needs fixing.

`overwriteManual: true` discards corrections deliberately. There is no way to do
it by accident.

### Reads never write

`GET /{userId}/monthly` returns the stored rows once generated. Before that it
returns a **preview** computed from punches that persists nothing. Either way a
read cannot overwrite a correction - which was a real hazard in an earlier
design, where a plain GET and payroll itself both recomputed and destroyed
whatever HR had fixed.

**Who can read whose attendance.** `GET /{userId}` (daily), `GET /{userId}/monthly`
and `GET /{userId}/records` are all self-service restricted: a plain `EMPLOYEE`
token can only ever read their own `userId`, a `SUPERVISOR` token only their
own direct reports plus themselves, and `HR`/`ADMIN` unrestricted within their
own company. Requesting someone else's attendance without that relationship
returns 404 (not 403 - see the tenant-isolation note in section 6), the exact
same shape a nonexistent `userId` would return. See [SECURITY.md](SECURITY.md)'s
self-service scoping section.

---

## 6. Corrections

```
PUT /api/attendance/{userId}/{date}
```

HR or ADMIN only (enforced by `@PreAuthorize`, not just a role field in the
body). `remarks` is mandatory - a correction without a reason is not
auditable. `updatedBy` (always the caller, not client-supplied - see the
Generate section above) and `updatedAt` are recorded and survive locking.

**Tenant-checked before anything else touches the record.** `{userId}` is
resolved through the same choke point every cross-company check in this app
uses, *before* the correction is applied - naming another company's `userId`
here 404s exactly like an unknown one would. This was a real gap found and
fixed in an earlier audit pass (`correctDay` and the lock/unlock endpoints
below resolved the record by `(userId, date)` directly, without ever
confirming the target belonged to the caller's own company - see
SECURITY_AUDIT.md, finding 2).

Devices miss punches. When one does, the day reads `INVALID_PUNCH` and would
silently become LOP. Two correction modes, because the real cases differ:

**Mode 1 — corrected punches.** The device captured the entry and missed the
exit; supply the times.

```json
{ "firstIn": "2026-06-25T06:00:00", "lastOut": "2026-06-25T15:00:00",
  "remarks": "Device missed the exit punch", "updatedBy": "HR001" }
```

Both times are required and `lastOut` must be after `firstIn` - it may fall on
the next day for a night shift. The times are run back through the **same**
`AttendanceCalculationService` a device punch takes, so a hand-fixed day derives
its hours, break, lateness and overtime by identical rules. There is no second
code path that can drift.

Supplying `status` as well overrides the derived one - the admin may know it was
half a day even though the times say otherwise.

**Mode 2 — forced status.** No punch exists to correct: the device was down, or
the employee worked off-site.

```json
{ "status": "PRESENT", "remarks": "Worked at the client site", "updatedBy": "HR001" }
```

Hours follow the shift, because declaring someone present means declaring they
worked the shift:

| Forced status | Working hours |
|---|---|
| `PRESENT` | the shift's `workingHours` |
| `HALF_DAY` | half of it |
| anything else | 0 |

Punch times are cleared, and late/early/overtime are all zeroed - there is no
evidence for them.

Either punch times or a status must be present. Neither is not a correction.

**Only generated days can be corrected.** Correcting a day that was never
generated returns 404 - generate the month first. Correcting a day with no
roster entry is impossible by construction: assign the shift first.

---

## 7. Locking and the payroll handoff

Payroll **refuses to run** against a period that was never generated:

```
400  Attendance has not been generated for SE10012 for 2026-06 -
     generate and review it before running payroll
```

That guard is the point of the whole design: nobody is paid off numbers nobody
looked at.

Generating payroll then **locks** every attendance day in the period, keeping an
already-paid month reproducible. To change it afterwards:

```
unlock  ->  correct  ->  regenerate payroll
POST /api/attendance/{userId}/unlock?month=2026-06
```

(No `actorId` parameter - same as `generatedBy`/`updatedBy` above, the actor
is always the bearer token's own identity. HR/ADMIN only, and tenant-checked
the same way corrections are.)

Regenerating supersedes the previous payroll revision rather than editing it, so
a slip printed last month can always be reproduced exactly.

The monthly summary (`emp_monthly_attendance_summary`) remains a cache, but of
the **stored days** rather than of raw punches - so LOP and payroll inherit
corrections automatically.

---

## 8. Shift configuration reference

| Field | Effect | Get it wrong and |
|---|---|---|
| `start_time` / `end_time` | Defines the shift and whether it crosses midnight | End not after start makes it a night shift |
| `working_hours` | Paid hours; the denominator for full/half day and the overtime baseline | Too low ⇒ phantom overtime every day; too high ⇒ everyone half-day |
| `break_minutes` | Unpaid break subtracted from the punch-to-punch span | Inflates or deflates every day's hours |
| `grace_minutes` | Tolerance before an entry counts as late. **Affects lateness only** | Only changes `lateCount` |
| `overtime_window_minutes` | How long after the scheduled end a punch still counts | **`0` destroys attendance** — see section 2 |

### Check it before you trust a month

`GET /api/shifts` returns two **derived** fields alongside the stored ones:

```json
{ "shiftCode": "NIGHT", "startTime": "06:00:00", "endTime": "19:00:00",
  "crossesMidnight": false, "spanHours": 13.00,
  "warnings": ["the shift spans 13.00h but only 8h are paid plus 0 break minutes,
                so anyone working the full shift books about 5.0h of overtime every day..."] }
```

**`crossesMidnight` is the one to read first.** Nobody sets it - it is inferred
from the times, and a shift named NIGHT stored as `06:00-19:00` is simply a day
shift with a misleading name. The engine cannot know otherwise. If you roster
night shifts and no shift reports `crossesMidnight: true`, that is the bug.

`warnings` flags configuration that is legal but will produce attendance nobody
wants - a zero overtime window, or paid hours far shorter than the shift span.
The same warnings are logged at startup.

`overtime_window_minutes` should be comfortably larger than your longest normal
overrun, and comfortably smaller than the gap to the next shift. 240 is the
seeded default.

---

## 9. Worked example: a real month that went wrong

Employee `SE10012`, June 2026, MORNING shift 06:00–19:00, `working_hours` 8,
`break_minutes` 60, `grace_minutes` 120, rostered every day, four Sundays off.
28 of 30 days had punches.

**Configured with `overtime_window_minutes = 0`:**

```
workingDays 26 | presentDays 3.0 | absentDays 23.0 | invalidPunches 22
totalHours 37.92 | overtimeHours 1.67 | lopDays 23.0
```

Almost the entire month was loss of pay. The window closed at **19:00 sharp**,
and nearly every exit punch was later than that — 19:50, 20:26, 20:38. Those
punches were discarded, leaving one punch per day. Only the six days where
*both* punches happened to fall before 19:00 survived.

**The same punches with `overtime_window_minutes = 240`:**

```
workingDays 26 | presentDays 23.0 | absentDays 3.0 | invalidPunches 0
totalHours 272.41 | overtimeHours 60.16 | lopDays 3.0
```

The remaining 3 LOP days are genuine: one day with no punch at all, two half
days, and one day where the employee left after 2.93 hours — below the 40%
threshold.

Note the 60 hours of overtime. That is a **second** configuration problem: a
13-hour shift span with `working_hours` set to 8 books roughly two hours of
overtime every single day. Either the shift times or the paid hours are wrong.

The lesson: both failures were configuration, not code, and both were invisible
until someone read the numbers. Generate a month and look at it before paying it.

---

## 10. Diagnosing a wrong day

| Symptom | Likely cause | Check |
|---|---|---|
| `INVALID_PUNCH` everywhere | `overtime_window_minutes` too small | `SELECT overtime_window_minutes FROM shift` |
| One day `INVALID_PUNCH` | Device genuinely missed a punch | Correct it — mode 1 |
| Day missing entirely | No roster row for that date | `GET /api/shift-schedules/{userId}` |
| `ABSENT` despite punches | Worked below the 40% threshold | Check `workingHours` on the day |
| Everyone suddenly late | `grace_minutes` too small, or shift start wrong | Compare `firstIn` against `start_time` |
| Huge overtime every day | `working_hours` lower than the real shift span | Compare `working_hours` to `end - start - break` |
| Night day has next day's punch | Two shifts too close together | Check the rest gap between consecutive days |
| Night day reads morning punches | The shift does not actually cross midnight | `GET /api/shifts` — is `crossesMidnight` true? |
| Correction vanished | Regenerated with `overwriteManual: true` | `recordStatus` will read `GENERATED` |
| Payroll returns 400 | Attendance not generated for the period | `POST /api/attendance/generate` |
| One category is half-days everywhere | A `LATE_ARRIVAL` rule with too small a grace | `GET /api/attendance-policy/effective?userId=&date=` |
| A policy rule seems to do nothing | Its scope reference matches no employee, or a more specific rule is disabled | Same endpoint - it names the rule that won and the ones it beat |
| LOP moved with no absence | A month-scoped penalty | `policy_lop_days` on the summary, and `attendance_policy_outcome` |
| Correction returns 400 "locked" | Payroll already ran | Unlock, correct, regenerate |

Start with `GET /api/attendance/{userId}/records?month=yyyy-MM`. It shows every
stored day with its punches, derived figures, status, provenance and remarks -
which is almost always enough to see what happened.

---

## 11. The attendance policy engine

Sections 2 to 4 describe one policy for everybody: grace on the shift, the
day/half-day cutoffs on `attendance_rule`, and late minutes recorded and then
ignored. Most companies do not work that way. Workers and managers are held to
different standards, and the second company to use this system asks for rules
the first has never needed.

So policy is configurable per **population**, on top of everything above.

### The model in one line

```
a typed rule + who it applies to + when it took effect  ->  resolved per employee per day
```

Two ideas carry it, and both are borrowed from designs already in this repo:

1. **The catalog is closed.** A rule is an enum constant plus a small typed,
   validated parameter set - never a formula in a database column. Attendance
   policy decides salary; an eval-able string cannot be tested, cannot be
   migrated, and cannot be explained to an employee disputing a deduction.
   Adding a rule *type* is a code change. Adding a *rule* is a form.
2. **A version is never edited, only succeeded.** Rules are append-only and
   resolved **as of the attendance date**, the same way `salary_revision`
   records how a wage got where it is. An August day is priced by the rule that
   was in force in August, whatever HR does in September.

### The rules

| Type | Scope | Decides |
|---|---|---|
| `MISSING_PUNCH` | day | A lone punch that looks like an entry becomes a half day instead of `INVALID_PUNCH` |
| `SHORT_HOURS` | day | The full/half-day cutoffs, as percentages **or absolute minutes**, per population |
| `LATE_ARRIVAL` | day | Grace, and what a late arrival costs |
| `DAY_OFF_WORK` | day | Whether working a weekly off earns overtime or a comp-off credit |
| `OVERTIME` | day | Who earns overtime, after how long, rounded to what block |
| `EARLY_EXIT_BUDGET` | **month** | A monthly budget of early-exit minutes, then a penalty per occurrence |
| `LATE_MARK_ACCUMULATION` | **month** | Nth late mark in a month costs a fraction of a day |

Five effects exist and no others: override the status, adjust overtime, credit
comp-off, add LOP days, or nothing.

### Who a rule applies to

```
EMPLOYEE > DESIGNATION > CATEGORY > DEPARTMENT > EMPLOYMENT TYPE > COMPANY > GLOBAL
```

**Most specific wins outright.** Scopes are never merged: a `CATEGORY` rule
replaces the `COMPANY` rule whole rather than inheriting its unset fields,
because a policy assembled from three rows is one nobody actually wrote.

A rule with `enabled = false` is an **answer, not an absence**. "Managers are
not tracked for lateness" is a disabled `LATE_ARRIVAL` at `CATEGORY=MANAGER`:
resolution picks it, finds it disabled, and applies nothing - the company rule
does *not* then take over. That is the only opt-out, deliberately.

### Day scope and month scope are not the same thing

This is the crux, and getting it wrong corrupts salaries quietly.

**Day rules** are pure functions of one day. They run inside
`AttendanceCalculationService.calculateDay` - the same method a hand-corrected
day goes through, so a correction cannot obey different rules from a device
reading. Regenerating one day alone gives the same answer as regenerating the
month.

**Month rules are stateful and order-dependent.** A budget spent day by day
cannot be evaluated during generation: regenerating day 12 alone would spend
minutes the rest of the month has already spent, and the same month would score
differently depending on which days had been touched since. So they are not
evaluated during generation at all. They are a **replay** over the month's
stored days in date order, from a zero accumulator, at summary-rebuild time -
recomputed from scratch every time, never incremented.

That is also why a `MANUAL` or locked day **counts toward a budget** while being
untouchable by day rules. A day HR corrected with an eighteen-minute early exit
is still an eighteen-minute early exit. It is safe because a month rule's only
output is a summary figure - it cannot write to a day even in principle.

### Where the numbers land

`emp_monthly_attendance_summary` gains `policy_lop_days` beside `lop_days`.
`lop_days` keeps its meaning - the total payroll reads - and the new column says
how much of it somebody chose. It is clamped: policy can never push LOP past the
days the employee was expected to work.

Every applied rule leaves a trace. `attendance_policy_application` holds one row
per day per rule that changed something; `attendance_policy_outcome` holds the
month-level ones. Both carry the rule, its version, its scope, the before and
after, and a rendered sentence:

```
HALF_DAY: in 09:16, 1 min beyond a 15 min grace on a 09:00 shift,
rule LATE_ARRIVAL v1 scoped CATEGORY=STAFF
```

The sentence is stored, not derived on read - the same reason `payroll`
snapshots `rule_pf_percent` instead of joining to `salary_rule`. Six months
later the rule has been superseded four times and the answer still has to work.

### Worked example: three late minutes, two different bills

`SE10012`, category `STAFF`, September 2026, GENERAL 09:00-18:00.

```
POST /api/attendance-policy/rules
{"scope":"CATEGORY","scopeRef":"STAFF","ruleType":"LATE_ARRIVAL",
 "effectiveFrom":"2026-09-01","enabled":true,
 "params":{"graceMinutes":15,"penaltyStatus":"HALF_DAY"}}
```

| Date | In | Out | Late | Status |
|---|---|---|---|---|
| 1 Sep | 09:14 | 18:05 | 0 | `PRESENT` |
| 2 Sep | 09:15 | 18:05 | **0** | `PRESENT` |
| 3 Sep | 09:16 | 18:30 | **1** | **`HALF_DAY`** |
| 4 Sep | 09:22 | 18:30 | 7 | `HALF_DAY` |

2 September is the boundary. `lateMinutes = max(0, firstIn - (start + grace))`,
so arriving at exactly 09:15 on a 15-minute grace is **not** late - the grace is
inclusive, matching the formula section 3 already uses rather than
reinterpreting it. One minute later costs half a day.

```
workingDays 4 | presentDays 3.0 | lopDays 1.0 | policyLopDays 0.0
```

Now add the monthly accumulation rule:

```
{"ruleType":"LATE_MARK_ACCUMULATION",
 "params":{"minimumLateMinutes":1,"occurrencesPerPenalty":3,"penaltyLopDays":0.5}}
```

Days 1 and 2 are late against the *shift's* own zero grace, so they are marks.
Days 3 and 4 were **already docked** by `LATE_ARRIVAL` and are excluded - nobody
is charged twice for one late arrival. Two marks is short of three, so:

```
policyLopDays 0.0 | lopDays 1.0     (unchanged)
```

Without that exclusion the same four days would have cost 1.5 days instead of
1.0, and the extra half day would have been for lateness already paid for.

### The single most damaging misconfiguration

Section 2's is `overtime_window_minutes = 0`. This engine's is worse, for one
specific reason:

> **`LATE_ARRIVAL` with `penaltyStatus: ABSENT` and a small grace, at `COMPANY`
> scope.** A five-minute grace and an absent penalty turns everybody who arrives
> at 09:06 into a full unpaid day - on a day they worked in full. One bad
> morning of traffic is a company-wide day of loss of pay.
>
> It is worse than the overtime-window bug because that one produced
> `INVALID_PUNCH`, which is visibly wrong and shows up as `invalidPunches` in
> the summary. This produces `ABSENT`, which is exactly what a genuinely absent
> day looks like. Nothing in the month's figures says anything went wrong. The
> only signal is that `presentDays` fell - and that is the number people expect
> a policy to move.

`grace_minutes` on the shift does **not** protect you: once a `LATE_ARRIVAL`
rule resolves, it measures lateness against its own grace, not the shift's.

### Check it before you save it

```
POST /api/attendance-policy/preview
{"month":"2026-09","rules":[ ...the rules you are about to save... ]}
```

Re-evaluates a real past month under a proposed rule set and returns the diff -
days changed with reasons, LOP delta and overtime delta per employee, and a
company-wide total - **without writing a single row**. It also warns about the
`ABSENT` penalty above, about a rule set that changes nothing (usually a typo'd
scope reference), and about a LOP delta large enough to want a second opinion.

Section 9's lesson was that both failures were configuration, not code, and both
were invisible until someone read the numbers. This is how to read them before
they are numbers anybody has been paid.

### Nothing is on by default

`attendance_policy_rule` ships **empty**, and the `GLOBAL` scope is seeded with
nothing - deliberately unlike `attendance_rule`, which seeds the values every
company was already using. A company that configures no rules produces
byte-identical attendance and payroll to what it produced before this engine
existed: resolution returns empty, the evaluators short-circuit before touching
the day, no trace rows are written, and `policy_lop_days` stays `0.0`. That is
covered by `AttendancePolicyEngineTest.noRulesConfiguredChangesNothing`, and by
every other test in the suite, all of which are unconfigured companies.

---

## 12. Invariants

Things the system guarantees, each covered by a test:

1. A punch is counted by **at most one** attendance day.
2. A punch inside a shift's contractual span is **never discarded** - the
   complementary half, and the one whose absence made a night shift's exit
   vanish while invariant 1 still held.
3. A day the employee was not rostered on is a blank `ABSENT` day, never a
   missing one - and never outside their joining/relieving window. With
   `includeUnrostered: false` it is not an attendance day at all.
4. A weekly off or mandatory holiday can never become loss of pay.
5. A lone punch is `INVALID_PUNCH`, never `ABSENT`.
6. A regeneration preserves `MANUAL` rows unless explicitly told otherwise.
7. A locked day is never modified by a regeneration.
8. A read never writes.
9. Payroll cannot run against attendance that was never generated.
10. A night shift belongs to the date it started on, across month and year ends.
11. Hand-corrected days obey identical calculation rules to device-read days -
    including attendance policy.
12. Regenerating an unchanged roster produces identical rows.
13. A company with no policy rules produces byte-identical output to what it
    produced before the policy engine existed.
14. A policy rule may only ever lower a day's value, except `MISSING_PUNCH`,
    which exists to rescue a day the device broke.
15. A policy rule is resolved by the attendance date, never by today - so a rule
    written in September cannot re-price August.
16. Loss of pay can never exceed the days the employee was expected to work.
17. A day already docked for lateness is not also counted as a late mark.

## 13. Not covered

- **No rest-gap *enforcement*** between consecutive shift assignments - an
  unworkable pair is logged and audited (`SHIFT_SCHEDULE_REST_GAP`), never
  refused (section 2).
- **No approval workflow on corrections** - an HR user's edit takes effect
  immediately. The audit trail records who and why, but nobody countersigns.
- **No partial-month proration on joining or leaving.** An employee who joins
  mid-month is measured against whatever roster exists for them.
- **No automatic regeneration** when leave is approved after attendance was
  generated. Approve leave *before* generating, or regenerate afterwards.
