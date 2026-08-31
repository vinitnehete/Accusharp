package com.accusharp.hrms;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.DailyAttendanceResponse;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.PayrollRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.attendance.AttendanceService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A shift owns its attendance window through to its own scheduled end, even
 * when that end falls on the next calendar day and the next calendar day has a
 * shift of its own.
 *
 * <p>The invariant {@link NightShiftMonthBoundaryTest} already covers is that a
 * punch is claimed by <em>at most</em> one day. That is only half of it, and on
 * its own it is satisfied by throwing the punch away: before this suite existed
 * a night shift followed by a morning shift lost its out-punch entirely and
 * both days were left holding one punch each, two {@code INVALID_PUNCH} days
 * and two days of loss of pay from a perfectly ordinary night's work.
 *
 * <p>So these tests assert the complementary half: no punch inside a shift's
 * contractual span is discarded, and the night shift actually closes.
 */
@SpringBootTest
class NightShiftWindowOwnershipTest {

    private static final YearMonth JUNE = YearMonth.of(2026, 6);
    private static final LocalDate D15 = LocalDate.of(2026, 6, 15);
    private static final LocalDate D16 = LocalDate.of(2026, 6, 16);
    private static final String EMPLOYEE = "EMP600";
    private static final String HR = "HR600";

    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private PayrollRepository payrollRepository;
    @Autowired private MonthlyAttendanceSummaryRepository monthlyAttendanceSummaryRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;
    @Autowired private AttendanceService attendanceService;

    private Shift night;
    private Shift nightNoOvertimeWindow;
    private Shift morning;
    private Shift general;
    private long punchId = 60_000;

    @BeforeEach
    void setUp() {
        payrollRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        monthlyAttendanceSummaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        punchId = 60_000;

        // The seeded definitions from DataSeeder, which is what production runs on.
        night = saveShift("NIGHT", LocalTime.of(18, 0), LocalTime.of(8, 0), 240);
        nightNoOvertimeWindow = saveShift("NIGHT_TIGHT", LocalTime.of(18, 0), LocalTime.of(8, 0), 0);
        morning = saveShift("MORNING", LocalTime.of(6, 0), LocalTime.of(19, 0), 240);
        general = saveShift("GENERAL", LocalTime.of(9, 0), LocalTime.of(19, 0), 240);

        saveEmployee(HR, "EMP-HR-600", Role.HR);
        saveEmployee(EMPLOYEE, "EMP-600", Role.EMPLOYEE);
    }

    // ---- the reported bug --------------------------------------------------

    @Test
    @DisplayName("a night shift keeps its out-punch when the next day is a morning shift")
    void nightShiftFollowedByMorningShiftStillCloses() {
        roster(D15, night, false);
        roster(D16, morning, false);
        punches(D15.atTime(20, 0), D16.atTime(5, 0));

        generate(JUNE);

        DailyAttendanceResponse fifteenth = dayOf(JUNE, D15);
        assertThat(fifteenth.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(fifteenth.firstIn()).isEqualTo(D15.atTime(20, 0));
        assertThat(fifteenth.lastOut()).isEqualTo(D16.atTime(5, 0));

        // The 16th has no punches of its own. It must not steal the night's exit.
        assertThat(dayOf(JUNE, D16).firstIn()).isNull();
    }

    @Test
    @DisplayName("punching out at the exact scheduled end is a present day, not two invalid ones")
    void punchingOutExactlyAtScheduledEndIsOwnedByTheNightShift() {
        // NIGHT ends 08:00; GENERAL's window would otherwise open at 08:00 too.
        roster(D15, night, false);
        roster(D16, general, false);
        punches(D15.atTime(18, 0), D16.atTime(8, 0));

        generate(JUNE);

        DailyAttendanceResponse fifteenth = dayOf(JUNE, D15);
        assertThat(fifteenth.status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(fifteenth.lastOut()).isEqualTo(D16.atTime(8, 0));
        assertThat(dayOf(JUNE, D16).firstIn()).isNull();
    }

    @Test
    @DisplayName("one minute either side of the scheduled end behaves the same way")
    void theBoundaryMinuteIsNotACliff() {
        roster(D15, night, false);
        roster(D16, general, false);
        punches(D15.atTime(18, 0), D16.atTime(7, 59));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(dayOf(JUNE, D15).lastOut()).isEqualTo(D16.atTime(7, 59));
    }

    @Test
    @DisplayName("a rostered week-off does not truncate the night shift before it")
    void rosteredWeekOffDoesNotStealTheNightShiftsExit() {
        // DefaultRosterService writes every Sunday exactly like this: a real
        // GENERAL row carrying weekOff = true. A day off has no shift to defend.
        roster(D15, night, false);
        roster(D16, general, true);
        punches(D15.atTime(18, 0), D16.atTime(8, 0));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(dayOf(JUNE, D15).lastOut()).isEqualTo(D16.atTime(8, 0));
        assertThat(dayOf(JUNE, D16).status()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
    }

    @Test
    @DisplayName("a zero overtime window still leaves the shift owning its own span")
    void zeroOvertimeWindowStillOwnsTheContractualSpan() {
        roster(D15, nightNoOvertimeWindow, false);
        roster(D16, general, false);
        punches(D15.atTime(18, 0), D16.atTime(8, 0));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(dayOf(JUNE, D15).lastOut()).isEqualTo(D16.atTime(8, 0));
    }

    // ---- punch selection ---------------------------------------------------

    @Test
    @DisplayName("the last punch inside the window is the exit, even with several after midnight")
    void severalPunchesAfterMidnightResolveToTheLastOneInsideTheWindow() {
        roster(D15, night, false);
        roster(D16, general, false);
        punches(D15.atTime(20, 1), D16.atTime(4, 58), D16.atTime(5, 5), D16.atTime(7, 30));

        generate(JUNE);

        DailyAttendanceResponse fifteenth = dayOf(JUNE, D15);
        assertThat(fifteenth.firstIn()).isEqualTo(D15.atTime(20, 1));
        assertThat(fifteenth.lastOut()).isEqualTo(D16.atTime(7, 30));
        assertThat(fifteenth.status()).isEqualTo(AttendanceStatus.PRESENT);
    }

    @Test
    @DisplayName("the next shift's own entry punch still belongs to the next shift")
    void theNextShiftKeepsItsOwnEntryPunch() {
        roster(D15, night, false);
        roster(D16, general, false);
        punches(D15.atTime(18, 5), D16.atTime(7, 50));
        punches(D16.atTime(9, 0), D16.atTime(18, 0));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).lastOut()).isEqualTo(D16.atTime(7, 50));
        assertThat(dayOf(JUNE, D16).firstIn()).isEqualTo(D16.atTime(9, 0));
        assertThat(dayOf(JUNE, D16).lastOut()).isEqualTo(D16.atTime(18, 0));
    }

    @Test
    @DisplayName("a genuinely missing exit is still an invalid punch, not a present day")
    void aGenuinelyMissingExitRemainsInvalid() {
        roster(D15, night, false);
        roster(D16, morning, false);
        punches(D15.atTime(20, 0));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).status()).isEqualTo(AttendanceStatus.INVALID_PUNCH);
    }

    // ---- invariants --------------------------------------------------------

    @Test
    @DisplayName("no punch inside a shift's contractual span is discarded, and none is counted twice")
    void everyPunchInsideACoreIsClaimedExactlyOnce() {
        // A week of the worst roster the scheduler can legally produce.
        roster(D15.minusDays(2), morning, false);
        roster(D15.minusDays(1), general, false);
        roster(D15, night, false);
        roster(D16, general, false);
        roster(D16.plusDays(1), night, false);
        roster(D16.plusDays(2), general, true);

        List<LocalDateTime> all = List.of(
                D15.minusDays(2).atTime(6, 0), D15.minusDays(2).atTime(15, 30),
                D15.minusDays(1).atTime(9, 0), D15.minusDays(1).atTime(19, 0),
                D15.atTime(18, 0), D16.atTime(8, 0),
                D16.atTime(9, 15), D16.atTime(19, 5),
                D16.plusDays(1).atTime(18, 0), D16.plusDays(2).atTime(8, 0));
        punches(all.toArray(new LocalDateTime[0]));

        generate(JUNE);

        List<DailyAttendanceResponse> days = attendanceService
                .getDailyAttendance(EMPLOYEE, JUNE.atDay(1), JUNE.atEndOfMonth());

        for (LocalDateTime punch : all) {
            long claims = days.stream()
                    .filter(day -> punch.equals(day.firstIn()) || punch.equals(day.lastOut()))
                    .count();
            // A punch may be neither first nor last on its day (a middle break
            // punch), so this asserts no punch is claimed by two different days.
            assertThat(claims)
                    .as("punch %s claimed by %d days", punch, claims)
                    .isLessThanOrEqualTo(1);
        }

        // And the two boundary punches that the old windowing discarded are present.
        assertThat(dayOf(JUNE, D15).lastOut()).isEqualTo(D16.atTime(8, 0));
        assertThat(dayOf(JUNE, D16).firstIn()).isEqualTo(D16.atTime(9, 15));
    }

    @Test
    @DisplayName("an unworkable roster resolves the same way every time, in the night shift's favour")
    void overlappingCoresResolveToTheEarlierShiftDate() {
        // NIGHT runs to 08:00 and MORNING starts at 06:00, so the two contractual
        // spans genuinely overlap: nobody could work both, and a punch at 06:30 is
        // inside each of them. There is no correct answer, only a deterministic
        // one - the earlier shift date takes it, so the night shift closes rather
        // than being left open, and the day is logged as a roster conflict.
        roster(D15, night, false);
        roster(D16, morning, false);
        punches(D15.atTime(18, 0), D16.atTime(6, 30));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(dayOf(JUNE, D15).lastOut()).isEqualTo(D16.atTime(6, 30));
        assertThat(dayOf(JUNE, D16).firstIn()).isNull();
    }

    @Test
    @DisplayName("regenerating an unchanged roster twice produces identical rows")
    void regenerationIsIdempotent() {
        roster(D15, night, false);
        roster(D16, general, false);
        punches(D15.atTime(18, 0), D16.atTime(8, 0), D16.atTime(9, 0), D16.atTime(18, 0));

        generate(JUNE);
        DailyAttendanceResponse firstRun15 = dayOf(JUNE, D15);
        DailyAttendanceResponse firstRun16 = dayOf(JUNE, D16);

        generate(JUNE);

        assertThat(dayOf(JUNE, D15)).isEqualTo(firstRun15);
        assertThat(dayOf(JUNE, D16)).isEqualTo(firstRun16);
    }

    @Test
    @DisplayName("a dry run reports what would change and writes nothing")
    void dryRunReportsWithoutWriting() {
        roster(D15, night, false);
        roster(D16, general, false);
        punches(D15.atTime(18, 0), D16.atTime(8, 0));

        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(JUNE);
        request.setUserIds(List.of(EMPLOYEE));
        request.setGeneratedBy(HR);
        request.setDryRun(true);
        var response = attendanceService.generate(request);

        assertThat(response.dryRun()).isTrue();
        assertThat(response.changes()).isNotEmpty();
        assertThat(response.changes())
                .anySatisfy(change -> {
                    assertThat(change.date()).isEqualTo(D15);
                    assertThat(change.previousStatus()).isNull();
                    assertThat(change.newStatus()).isEqualTo(AttendanceStatus.PRESENT);
                });
        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate(EMPLOYEE, D15))
                .as("a dry run must not persist anything")
                .isEmpty();
    }

    @Test
    @DisplayName("generating a month before the next month's roster exists never double-counts")
    void generatingBeforeTheNextPeriodsRosterExistsNeverDoubleCounts() {
        LocalDate lastDay = LocalDate.of(2026, 6, 30);
        LocalDate julyFirst = LocalDate.of(2026, 7, 1);
        roster(lastDay, night, false);
        punches(lastDay.atTime(18, 5), julyFirst.atTime(9, 0), julyFirst.atTime(18, 0));

        // June generated while July's roster does not yet exist.
        generate(JUNE);

        // July's roster arrives afterwards, and July is generated.
        roster(julyFirst, general, false);
        generate(YearMonth.of(2026, 7));

        DailyAttendanceResponse june30 = dayOf(JUNE, lastDay);
        DailyAttendanceResponse july1 = dayOf(YearMonth.of(2026, 7), julyFirst);

        long claims = 0;
        if (julyFirst.atTime(9, 0).equals(june30.lastOut())) {
            claims++;
        }
        if (julyFirst.atTime(9, 0).equals(july1.firstIn())) {
            claims++;
        }
        assertThat(claims)
                .as("1 July 09:00 belongs to July's general shift, and to it alone")
                .isEqualTo(1);
        assertThat(july1.firstIn()).isEqualTo(julyFirst.atTime(9, 0));
    }

    @Test
    @DisplayName("the previous month's trailing day is refreshed, but never past a lock")
    void theNeighbourRefreshRespectsLockedAndManualDays() {
        LocalDate lastDay = LocalDate.of(2026, 6, 30);
        LocalDate julyFirst = LocalDate.of(2026, 7, 1);
        roster(lastDay, night, false);
        punches(lastDay.atTime(18, 5), julyFirst.atTime(9, 0), julyFirst.atTime(18, 0));

        generate(JUNE);
        // June generated with no July roster, so 30 June currently claims 1 July's
        // 09:00 entry as its own exit. Payroll then runs and freezes June.
        attendanceService.lockMonth(EMPLOYEE, JUNE);
        LocalDateTime frozenLastOut = dayOf(JUNE, lastDay).lastOut();
        assertThat(frozenLastOut).isEqualTo(julyFirst.atTime(9, 0));

        roster(julyFirst, general, false);
        generate(YearMonth.of(2026, 7));

        assertThat(dayOf(JUNE, lastDay).lastOut())
                .as("a locked day is never rewritten, not even by the neighbour refresh")
                .isEqualTo(frozenLastOut);
    }

    @Test
    @DisplayName("the neighbour refresh does not create days the previous month never generated")
    void theNeighbourRefreshOnlyRefreshesWhatAlreadyExists() {
        LocalDate lastDay = LocalDate.of(2026, 6, 30);
        LocalDate julyFirst = LocalDate.of(2026, 7, 1);
        roster(lastDay, night, false);
        roster(julyFirst, general, false);
        punches(lastDay.atTime(18, 5), julyFirst.atTime(7, 50));

        // July generated first; June has never been generated at all.
        generate(YearMonth.of(2026, 7));

        assertThat(dailyAttendanceRepository.findByUserIdAndAttendanceDate(EMPLOYEE, lastDay))
                .as("generating July must not quietly bring 30 June into existence")
                .isEmpty();
    }

    @Test
    @DisplayName("a night shift nobody worked does not steal the next morning's entry punch")
    void anUnworkedNightShiftDoesNotClaimTheMorningsEntry() {
        // Real data, SE10098, 30-31 July 2026. The 30th's night shift was never
        // worked - there is no evening punch anywhere - and the 31st's morning
        // was worked normally, 07:18 to 19:13. Because NIGHT runs to 08:00 and
        // MORNING starts at 06:00, the 07:18 entry sits inside both cores.
        // Resolving that on shift date alone invented a night shift out of the
        // morning's entry punch and left both days INVALID_PUNCH; the morning
        // has a punch of its own that nothing else can claim, the night has
        // none, and that is what settles it.
        roster(D15, night, false);
        roster(D16, morning, false);
        punches(D16.atTime(7, 18), D16.atTime(19, 13));

        generate(JUNE);

        assertThat(dayOf(JUNE, D16).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(dayOf(JUNE, D16).firstIn()).isEqualTo(D16.atTime(7, 18));
        assertThat(dayOf(JUNE, D16).lastOut()).isEqualTo(D16.atTime(19, 13));
        assertThat(dayOf(JUNE, D15).status())
                .as("a night nobody worked is absent, not a phantom shift built from the morning's punch")
                .isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    @DisplayName("a night shift that was worked still keeps its exit, morning shift or not")
    void aWorkedNightShiftStillWinsTheContestedPunch() {
        // The mirror of the case above: here the night has its own evening punch,
        // so it is the night that has independent evidence and the 07:10 exit
        // belongs to it - not to the morning that follows.
        roster(D15, night, false);
        roster(D16, morning, false);
        punches(D15.atTime(18, 53), D16.atTime(7, 10));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(dayOf(JUNE, D15).firstIn()).isEqualTo(D15.atTime(18, 53));
        assertThat(dayOf(JUNE, D15).lastOut()).isEqualTo(D16.atTime(7, 10));
        assertThat(dayOf(JUNE, D16).status()).isEqualTo(AttendanceStatus.ABSENT);
    }

    @Test
    @DisplayName("a night shift running late keeps its own exit when the morning was not worked")
    void aLateNightExitIsNotStolenByAnUnworkedMorning() {
        // Punching out at 08:05 is five minutes past the night's scheduled end and
        // squarely inside the morning's core. The night has an evening punch and
        // the morning has nothing of its own, so the overtime punch stays with
        // the shift that actually ran over.
        roster(D15, night, false);
        roster(D16, morning, false);
        punches(D15.atTime(18, 0), D16.atTime(8, 5));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).status()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(dayOf(JUNE, D15).lastOut()).isEqualTo(D16.atTime(8, 5));
    }

    // ---- duplicate punches -------------------------------------------------

    @Test
    @DisplayName("a punch the device re-synced twice stays an invalid punch, not an absence")
    void duplicatePunchesDoNotHideADeviceError() {
        roster(D15, night, false);
        // The same real punch, delivered twice by a device re-sync.
        punches(D15.atTime(20, 0), D15.atTime(20, 0));

        generate(JUNE);

        assertThat(dayOf(JUNE, D15).status())
                .as("two rows for one physical punch must not read as a zero-length day")
                .isEqualTo(AttendanceStatus.INVALID_PUNCH);
    }

    // ---- fixtures ----------------------------------------------------------

    private DailyAttendanceResponse dayOf(YearMonth month, LocalDate date) {
        return attendanceService.getMonthlyAttendance(EMPLOYEE, month).days().stream()
                .filter(d -> d.attendanceDate().equals(date)).findFirst().orElseThrow();
    }

    private Shift saveShift(String code, LocalTime start, LocalTime end, int overtimeWindow) {
        return shiftRepository.save(Shift.builder()
                .shiftCode(code).shiftName(code)
                .startTime(start).endTime(end)
                .workingHours(8).breakMinutes(0).graceMinutes(120)
                .overtimeWindowMinutes(overtimeWindow).build());
    }

    private void roster(LocalDate date, Shift shift, boolean weekOff) {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(EMPLOYEE).shiftDate(date).shift(shift).weekOff(weekOff).build());
    }

    private void punches(LocalDateTime... times) {
        List<DeviceLog> logs = new ArrayList<>();
        for (LocalDateTime at : times) {
            logs.add(DeviceLog.builder().deviceLogId(punchId++).deviceId(1L)
                    .userId(EMPLOYEE).logDate(at).build());
        }
        deviceLogRepository.saveAll(logs);
    }

    private void generate(YearMonth month) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(month);
        request.setUserIds(List.of(EMPLOYEE));
        request.setGeneratedBy(HR);
        attendanceService.generate(request);
    }

    private void saveEmployee(String userId, String code, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode(code).employeeName(userId)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("26000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false).build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}
