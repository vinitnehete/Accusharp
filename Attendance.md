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

1. **The roster is the source of expectation.** A day the employee was not
   scheduled on is not an attendance day at all - not present, not absent,
   simply not a day. This is why *absent* can only ever mean "expected to work
   and did not".
2. **Attendance is a stored artifact, not a live view.** It is generated once,
   reviewed by a human, corrected where the device got it wrong, then frozen.
   Payroll reads the frozen record, never the raw punches.

---

## 1. The inputs

| Input | Table | Who writes it | Role |
|---|---|---|---|
| Raw punches | `device_logs` | The eSSL device's middleware, directly | What actually happened |
| Roster | `emp_attendance_shift` | HR / supervisors, via the API | What was expected |
| Shift master | `shift` | HR, via the API | How to interpret the punches |
| Holiday calendar | `holiday` | HR, via the API | Which days are not working days |
| Approved leave | `leave_request` | The leave workflow | Why an absence is excused |

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

- **Opens 60 minutes before the start.** People badge in early. This is a fixed
  constant (`ENTRY_WINDOW_BUFFER`), not configurable.
- **Closes `overtimeWindowMinutes` after the scheduled end.** Configurable per
  shift, default 240 (4 hours). The window is **exclusive** at the end.

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

### Windows are exclusive

A night shift's window crosses midnight and, with an overtime window on top, can
reach into the hours the *next* scheduled day is already collecting for:

```
30 Jun NIGHT  18:00-08:00 (+240)   window ...............-> 1 Jul 12:00
 1 Jul GENERAL 09:00-18:00         window   1 Jul 08:00 ->
                                            |__overlap__|
```

Left alone, a punch in the overlap is claimed by **both** days: the night shift
swallows the next morning's entry and reports it as its own exit, inflating June
and corrupting July.

So a day's window is **truncated where the next scheduled day's window opens**:

```
effectiveEnd(day) = min(rawEnd(day), windowStart(next rostered day))
```

It only ever shrinks. Where shifts do not overlap the full overtime window
survives untouched, and two consecutive night shifts never collide in the first
place. The roster is read **one day either side** of the requested range so the
boundary is known at both ends - which is what makes the last night shift of a
month hand over correctly to the first day of the next.

> **Not validated:** the roster will happily accept a night shift (ending 08:00)
> followed by a morning shift (starting 06:00), which nobody can physically
> work. The engine no longer double-counts the handover punch, but garbage in is
> still garbage out. A minimum-rest-gap check on shift assignment is not built.

---

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

**Break.** With **four or more punches and an even count**, the middle pairs are
treated as real out/in cycles and the break is the actual time spent outside.
Otherwise the shift's configured `breakMinutes` applies. An odd count of five or
more falls back to the configured break - the pairs cannot be trusted.

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

---

## 4. Stage three: rolling up a month

**Working days** - the denominator everything else hangs off:

```
workingDays = rostered days that are neither a weekly off nor a mandatory holiday
```

Optional holidays stay working days. Days with no roster row are not counted at
all, so skipping a holiday during bulk assignment removes it from the month
entirely.

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
{ "month": "2026-06", "userIds": ["SE10012"], "overwriteManual": false }
```

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
| Correction returns 400 "locked" | Payroll already ran | Unlock, correct, regenerate |

Start with `GET /api/attendance/{userId}/records?month=yyyy-MM`. It shows every
stored day with its punches, derived figures, status, provenance and remarks -
which is almost always enough to see what happened.

---

## 11. Invariants

Things the system guarantees, each covered by a test:

1. A punch is counted by **exactly one** attendance day.
2. A day the employee was not rostered on is not an attendance day.
3. A weekly off or mandatory holiday can never become loss of pay.
4. A lone punch is `INVALID_PUNCH`, never `ABSENT`.
5. A regeneration preserves `MANUAL` rows unless explicitly told otherwise.
6. A locked day is never modified by a regeneration.
7. A read never writes.
8. Payroll cannot run against attendance that was never generated.
9. A night shift belongs to the date it started on, across month and year ends.
10. Hand-corrected days obey identical calculation rules to device-read days.

## 12. Not covered

- **No rest-gap validation** between consecutive shift assignments (section 2).
- **No approval workflow on corrections** - an HR user's edit takes effect
  immediately. The audit trail records who and why, but nobody countersigns.
- **No partial-month proration on joining or leaving.** An employee who joins
  mid-month is measured against whatever roster exists for them.
- **No automatic regeneration** when leave is approved after attendance was
  generated. Approve leave *before* generating, or regenerate afterwards.
