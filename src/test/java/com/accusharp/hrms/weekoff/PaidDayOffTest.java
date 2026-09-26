package com.accusharp.hrms.weekoff;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.entity.AttendancePolicyApplication;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.repository.AttendancePolicyApplicationRepository;
import com.accusharp.hrms.repository.AttendancePolicyOutcomeRepository;
import com.accusharp.hrms.repository.AttendancePolicyRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.DailyAttendanceRepository;
import com.accusharp.hrms.repository.DeviceLogRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.MonthlyAttendanceSummaryRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.repository.ShiftScheduleRepository;
import com.accusharp.hrms.service.attendance.AttendanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DAY_OFF_WORK = PAID_DAY}: a worked weekly off counts as a paid day.
 *
 * <p>The case it exists for is the swap day-wise labour makes all the time -
 * miss a Tuesday, work the Sunday instead. Without this, a weekly-off day is
 * excluded from {@code presentDays} whatever happened on it, so the worker is
 * paid one day short and the Sunday's hours fall through to monthly overtime,
 * which pays nothing at all to someone who is not overtime-eligible.
 *
 * <p>The credit is <b>added to {@code presentDays} without adding the day to
 * {@code workingDays}</b>. For a day-wise worker that is one more paid day, up
 * to the existing cap. For anyone carrying LOP it offsets an absence elsewhere
 * in the month rather than creating pay beyond it. And it is opt-in by scope,
 * so nobody's pay moves until a company configures it.
 *
 * <p>Every scenario rosters exactly two days - an unworked Tuesday and a worked
 * Sunday marked as the weekly off - and generates with
 * {@code includeUnrostered = false}, so the numbers are about those two days
 * alone. Shift: 09:00-18:00, no break.
 */
@SpringBootTest
class PaidDayOffTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final String HR = "HR970";
    private static final String WORKER = "PD970";

    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 1);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 9, 6);

    private static final String PAID_DAY_ON_WEEKLY_OFF =
            "{\"onWeeklyOff\":\"PAID_DAY\",\"onHoliday\":\"OVERTIME_PAY\","
                    + "\"fullCreditMinutes\":480,\"halfCreditMinutes\":240}";

    @Autowired private AttendanceService attendanceService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository summaryRepository;
    @Autowired private AttendancePolicyRuleRepository policyRuleRepository;
    @Autowired private AttendancePolicyApplicationRepository policyApplicationRepository;
    @Autowired private AttendancePolicyOutcomeRepository policyOutcomeRepository;

    private Company company;
    private Shift general;
    private long punchId = 97_000;

    @BeforeEach
    void setUp() {
        clean();
        punchId = 97_000;

        company = companyRepository.save(Company.builder()
                .companyCode("PAIDDAY-CO").companyName("Paid Day Co").status(RecordStatus.ACTIVE).build());
        general = shiftRepository.save(Shift.builder()
                .company(company).shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(0).graceMinutes(15).overtimeWindowMinutes(240)
                .build());

        saveEmployee(HR, Role.HR, EmployeeStatus.PERMANENT);
    }

    /** Policy rules point at the company, so they have to go before it does. */
    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- the guarantee -----------------------------------------------------

    @Test
    @DisplayName("with no rule configured, a worked weekly off earns no paid day - exactly as before")
    void withoutARuleNothingChanges() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE);
        missedTuesdayWorkedSunday(WORKER, "09:00", "18:00");

        generate(WORKER);

        MonthlyAttendanceSummary summary = summary(WORKER);
        assertThat(summary.getWorkingDays()).isEqualTo(1);
        assertThat(summary.getPresentDays()).isEqualByComparingTo("0.0");
        assertThat(summary.getPaidDayOffDays()).isEqualByComparingTo("0.0");
    }

    // ---- the swap ----------------------------------------------------------

    @Test
    @DisplayName("missing a Tuesday and working the Sunday instead pays the Sunday as a day")
    void paidDayCountsTheWorkedWeeklyOff() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE);
        paidDayRule(RuleScope.EMPLOYMENT_TYPE, "DAY_WISE");
        missedTuesdayWorkedSunday(WORKER, "09:00", "18:00");

        generate(WORKER);

        MonthlyAttendanceSummary summary = summary(WORKER);
        // The Sunday is credited into presentDays...
        assertThat(summary.getPresentDays()).isEqualByComparingTo("1.0");
        assertThat(summary.getPaidDayOffDays()).isEqualByComparingTo("1.0");
        // ...without becoming a day they were expected to work.
        assertThat(summary.getWorkingDays()).isEqualTo(1);
        assertThat(summary.getWeekOffDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("the credit is written to the trace, with an explanation somebody can read back")
    void creditIsTraced() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE);
        paidDayRule(RuleScope.EMPLOYMENT_TYPE, "DAY_WISE");
        missedTuesdayWorkedSunday(WORKER, "09:00", "18:00");

        generate(WORKER);

        List<AttendancePolicyApplication> trace = policyApplicationRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(WORKER, SUNDAY, SUNDAY);
        assertThat(trace).singleElement().satisfies(row -> {
            assertThat(row.getRuleType()).isEqualTo(RuleType.DAY_OFF_WORK);
            assertThat(row.getPaidDayCredit()).isEqualByComparingTo("1");
            assertThat(row.getExplanation()).contains("paid day");
        });
    }

    @Test
    @DisplayName("a short stint earns half a day, and less than that earns nothing")
    void creditTiers() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE);
        paidDayRule(RuleScope.EMPLOYMENT_TYPE, "DAY_WISE");
        // 250 minutes: past the 240-minute half-day tier, short of the 480 full.
        missedTuesdayWorkedSunday(WORKER, "09:00", "13:10");

        generate(WORKER);

        assertThat(summary(WORKER).getPresentDays()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("ten minutes on a Sunday is not a paid day, even though the day reads PRESENT")
    void aFewMinutesIsNotAPaidDay() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE);
        paidDayRule(RuleScope.EMPLOYMENT_TYPE, "DAY_WISE");
        // Any worked day off reads PRESENT whatever its length - see
        // AttendanceCalculationService#resolveWorkedStatus. Crediting that
        // status directly would pay a full day for badging in and out.
        missedTuesdayWorkedSunday(WORKER, "09:00", "09:10");

        generate(WORKER);

        assertThat(summary(WORKER).getPresentDays()).isEqualByComparingTo("0.0");
        assertThat(policyApplicationRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(WORKER, SUNDAY, SUNDAY))
                .isEmpty();
    }

    @Test
    @DisplayName("a rule scoped to DAY_WISE leaves a contract employee on the same roster untouched")
    void ruleIsScopedToThePopulationItNames() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE);
        saveEmployee("PD971", Role.EMPLOYEE, EmployeeStatus.CONTRACT);
        paidDayRule(RuleScope.EMPLOYMENT_TYPE, "DAY_WISE");
        missedTuesdayWorkedSunday(WORKER, "09:00", "18:00");
        missedTuesdayWorkedSunday("PD971", "09:00", "18:00");

        generateFor(List.of(WORKER, "PD971"));

        assertThat(summary(WORKER).getPresentDays()).isEqualByComparingTo("1.0");
        assertThat(summary("PD971").getPresentDays()).isEqualByComparingTo("0.0");
    }

    @Test
    @DisplayName("resyncing the summary from the stored days keeps the credit")
    void resyncKeepsTheCredit() {
        saveEmployee(WORKER, Role.EMPLOYEE, EmployeeStatus.DAY_WISE);
        paidDayRule(RuleScope.EMPLOYMENT_TYPE, "DAY_WISE");
        missedTuesdayWorkedSunday(WORKER, "09:00", "18:00");
        generate(WORKER);

        // Every report runs this first. The credit has to be read back off the
        // stored trace, the way comp-off already is, or the first report opened
        // after generation would quietly take the day back.
        attendanceService.syncSummaries(PERIOD);

        assertThat(summary(WORKER).getPresentDays()).isEqualByComparingTo("1.0");
    }

    // ---- fixtures ----------------------------------------------------------

    private void missedTuesdayWorkedSunday(String userId, String in, String out) {
        roster(userId, TUESDAY, false);
        roster(userId, SUNDAY, true);
        punch(userId, SUNDAY.atTime(LocalTime.parse(in)));
        punch(userId, SUNDAY.atTime(LocalTime.parse(out)));
    }

    private void paidDayRule(RuleScope scope, String scopeRef) {
        policyRuleRepository.save(AttendancePolicyRule.builder()
                .company(company).scope(scope).scopeRef(scopeRef).ruleType(RuleType.DAY_OFF_WORK)
                .version(1).effectiveFrom(LocalDate.of(2026, 1, 1)).enabled(true)
                .params(PAID_DAY_ON_WEEKLY_OFF).createdAt(Instant.now()).createdBy(HR)
                .build());
    }

    private void generate(String userId) {
        generateFor(List.of(userId));
    }

    private void generateFor(List<String> userIds) {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(userIds);
        request.setGeneratedBy(HR);
        request.setIncludeUnrostered(false);
        attendanceService.generate(request);
    }

    private MonthlyAttendanceSummary summary(String userId) {
        return summaryRepository.findByUserIdAndMonth(userId, PERIOD.toString()).orElseThrow();
    }

    private void roster(String userId, LocalDate date, boolean weekOff) {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId(userId).shiftDate(date).shift(general).weekOff(weekOff).assignedBy(HR).build());
    }

    private void punch(String userId, LocalDateTime at) {
        deviceLogRepository.save(DeviceLog.builder()
                .deviceLogId(punchId++).deviceId(97L).userId(userId).logDate(at).build());
    }

    private Employee saveEmployee(String userId, Role role, EmployeeStatus status) {
        return employeeRepository.saveAndFlush(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(status).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .overtimeEligible(false)
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }

    private void clean() {
        policyApplicationRepository.deleteAll();
        policyOutcomeRepository.deleteAll();
        policyRuleRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        summaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        companyRepository.deleteAll();
    }
}
