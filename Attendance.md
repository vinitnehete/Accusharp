# Accusharp

Accusharp is a Spring Boot service that turns raw biometric device punches into daily and monthly attendance records, and manages the employee shift roster used to interpret those punches.

## Tech Stack

| Concern         | Choice                                  |
|------------------|------------------------------------------|
| Language / runtime | Java 21                                |
| Framework        | Spring Boot 4.1.0 (Spring Web, Spring Data JPA) |
| Database         | MySQL (`alsama` schema)                 |
| Build            | Maven (`mvnw`)                          |
| Boilerplate      | Lombok                                  |
| Logging          | SLF4J, structured `key=value` log lines |

## Architecture Overview

Accusharp is a single Spring Boot module organized in classic layers: **Controller → Service → Repository → Database**, plus a set of DTOs for API payloads and an enum that encodes shift business rules.

```
                         ┌─────────────────────────┐
                         │   eSSL Biometric Device   │
                         │  (writes punches directly  │
                         │   into device_logs table)  │
                         └────────────┬─────────────┘
                                      │ raw punches
                                      ▼
┌───────────────┐   HTTP    ┌─────────────────────────────────────────────┐
│  API Consumer  │ ────────▶│               Accusharp (Spring Boot)         │
│ (HR / payroll  │           │                                               │
│  frontend, etc)│           │  ┌─────────────────────────────────────────┐  │
└───────────────┘           │  │              Controller layer             │  │
                             │  │  AttendanceController   ShiftController   │  │
                             │  │  /api/attendance/*      /api/shifts/*     │  │
                             │  └───────────────────┬───────────────────────┘  │
                             │                      │ DTOs                     │
                             │  ┌───────────────────▼───────────────────────┐  │
                             │  │               Service layer               │  │
                             │  │  AttendanceService       ShiftService      │  │
                             │  │  - daily attendance      - save shift      │  │
                             │  │  - monthly attendance    - bulk save shift │  │
                             │  │    (shift-aware calc)    - get shifts      │  │
                             │  └───────────────────┬───────────────────────┘  │
                             │                      │ entities                 │
                             │  ┌───────────────────▼───────────────────────┐  │
                             │  │             Repository layer              │  │
                             │  │  DeviceLogRepository                      │  │
                             │  │  ShiftRepository                          │  │
                             │  │  MonthlyAttendanceSummaryRepository       │  │
                             │  │  (Spring Data JPA, one native query)      │  │
                             │  └───────────────────┬───────────────────────┘  │
                             └──────────────────────┼───────────────────────────┘
                                                     │ JDBC
                                                     ▼
                             ┌─────────────────────────────────────────────┐
                             │                MySQL: alsama                  │
                             │  device_logs                                  │
                             │  emp_attendance_shift                         │
                             │  emp_monthly_attendance_summary               │
                             └─────────────────────────────────────────────┘
```

## Layers

### Controller layer (`controller/`)

REST entry points. Thin — they bind request parameters/bodies to DTOs and delegate to a service.

- **`AttendanceController`** — `/api/attendance`
  - `GET /{userId}` — daily attendance for an optional `fromDate`/`toDate` window, built straight from raw punches.
  - `GET /{userId}/monthly?month=yyyy-MM` — shift-aware monthly attendance report.
- **`ShiftController`** — `/api/shifts`
  - `POST /` — create/update a single day's shift assignment.
  - `GET /{userId}` — list all shifts for a user.
  - `POST /bulk` — assign the same shift to a user across a date range.

### Service layer (`service/`)

Business logic lives here; controllers and repositories stay dumb.

- **`AttendanceService`**
  - `getAttendance` — daily view sourced directly from `device_logs` via a native aggregate query (first-in/last-out per calendar day).
  - `getMonthlyAttendance` — the core domain logic:
    1. Loads the user's shift roster for the month from `ShiftRepository`.
    2. For each shift day, computes a punch window (`calculateDay`) — a **night shift's window spans into the next calendar day**, since its end time is after midnight.
    3. Pulls punches inside that window from `DeviceLogRepository` and derives first-in, last-out, total hours, and overtime (over 8h).
    4. Flags days with fewer than 2 punches as `invalidPunch`.
    5. Aggregates presence/overtime and persists a `MonthlyAttendanceSummary` (upsert by `userId` + `month`) as a cached rollup.
- **`ShiftService`**
  - `saveShift` — upserts a single shift by `(userId, shiftDate)`.
  - `saveBulkShift` — expands a date range into individual `Shift` rows and batch-saves them.
  - `getShift` — lists a user's shifts; throws if none exist.

### Repository layer (`repository/`)

Spring Data JPA interfaces over three tables. `DeviceLogRepository.findDailyAttendance` is the one native SQL query in the app (uses `GROUP BY DATE(log_date)` to roll punches up to a day), exposed through the `DailyAttendanceProjection` interface.

### Domain model (`entity/`, `enums/`)

| Entity | Table | Purpose |
|---|---|---|
| `DeviceLog` | `device_logs` | Raw punch record written by the eSSL biometric device. Read-only from the app's perspective — no controller/service writes to it. |
| `Shift` | `emp_attendance_shift` | A user's assigned shift (`MORNING`/`NIGHT`) for a given date. Unique on `(user_id, shift_date)`. |
| `MonthlyAttendanceSummary` | `emp_monthly_attendance_summary` | Cached monthly rollup (present days, invalid punches, total/overtime hours), recomputed and upserted every time `getMonthlyAttendance` runs. |

`ShiftType` (enum) encodes the shift schedule as business rules, not just labels:
- `MORNING`: 06:00 – 20:00
- `NIGHT`: 18:00 – 08:00 (crosses midnight — handled explicitly in `AttendanceService.calculateDay`)

### DTOs (`dto/`)

Request/response shapes keep the entities off the wire:
`ShiftRequest`, `BulkShiftRequest` (inbound), `DailyAttendanceResponse`, `DailyShiftAttendanceResponse`, `MonthlyAttendanceResponse` (outbound).

## Data Flow: Monthly Attendance (key use case)

```
GET /api/attendance/{userId}/monthly?month=2026-08
        │
        ▼
AttendanceController.getMonthlyAttendance
        │
        ▼
AttendanceService.getMonthlyAttendance
        │
        ├─▶ ShiftRepository.findAllByUserIdAndShiftDateBetween   (this month's roster)
        │
        ├─▶ for each Shift:
        │      compute [windowStart, windowEnd)  (NIGHT shift → windowEnd on day+1)
        │      DeviceLogRepository.findAllByUserIdAndLogDateGreaterThanEqual...
        │      → first punch = check-in, last punch = check-out
        │      → totalHours, overtimeHours (>8h), invalidPunch (<2 punches)
        │
        ├─▶ aggregate: presentDays, invalidPunches, totalHours, overtimeHours
        │
        ├─▶ MonthlyAttendanceSummaryRepository upsert (cache the rollup)
        │
        ▼
MonthlyAttendanceResponse  (summary + per-day breakdown)
```

## Notable Design Points

- **Device integration is one-way and implicit.** There's no ingestion endpoint in this codebase — `device_logs` is assumed to be populated externally (by the eSSL device/its middleware writing straight to MySQL). Accusharp only reads it.
- **Night-shift midnight crossover** is the one piece of non-obvious domain logic, handled centrally in `AttendanceService.calculateDay` rather than scattered across callers.
- **Monthly summaries are a write-through cache**, not a source of truth — they're recomputed from `device_logs` + `emp_attendance_shift` on every request and overwritten.
- **No auth/security layer** is present yet — all endpoints are unauthenticated.

## Configuration

`src/main/resources/application.properties`:
- Server port `8080`
- MySQL connection to `jdbc:mysql://localhost:3306/alsama`
- `spring.jpa.hibernate.ddl-auto=update` (schema auto-managed by Hibernate — fine for dev, worth revisiting for production)
- Multipart upload limits set (2MB) though no file-upload endpoint currently exists

## Running Locally

```bash
./mvnw spring-boot:run
```

Requires a local MySQL instance with an `alsama` database reachable at `localhost:3306` (see credentials in `application.properties`).
