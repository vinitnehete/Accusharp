package com.accusharp.hrms.policy;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.entity.AttendancePolicyRule;
import com.accusharp.hrms.entity.Category;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.DailyAttendance;
import com.accusharp.hrms.entity.DeviceLog;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.entity.ShiftSchedule;
import com.accusharp.hrms.enums.AttendanceStatus;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.RuleType;
import com.accusharp.hrms.repository.*;
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
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The policy engine end to end, through a real generation run.
 *
 * <p>The first test here is the one that matters most: <b>a company that
 * configures nothing must produce exactly what it produced before this feature
 * existed.</b> Every other test in this suite adds a rule and asserts it moved
 * the numbers - but if the first one ever fails, none of the rest is worth
 * anything, because the feature would be changing what already-running clients
 * are paid.
 *
 * <p>September 2026, GENERAL 09:00-18:00, 8 paid hours, 60 min break, no shift
 * grace. Matches the worked examples in section 8 of
 * {@code docs/design/attendance-policy-engine.md}.
 */
@SpringBootTest
class AttendancePolicyEngineTest {

    private static final YearMonth PERIOD = YearMonth.of(2026, 9);

    @Autowired private AttendanceService attendanceService;
    @Autowired private CompanyRepository companyRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ShiftRepository shiftRepository;
    @Autowired private ShiftScheduleRepository shiftScheduleRepository;
    @Autowired private DeviceLogRepository deviceLogRepository;
    @Autowired private DailyAttendanceRepository dailyAttendanceRepository;
    @Autowired private MonthlyAttendanceSummaryRepository summaryRepository;
    @Autowired private AttendancePolicyRuleRepository policyRuleRepository;
    @Autowired private AttendancePolicyApplicationRepository policyApplicationRepository;
    @Autowired private AttendancePolicyOutcomeRepository policyOutcomeRepository;
    @Autowired private HolidayRepository holidayRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;

    private Company company;
    private Shift shift;
    private long punchId = 1;

    /**
     * Clears the rules this class created before the next test class runs.
     *
     * <p>Every row this class writes, in foreign-key-safe order. Both {@code
     * attendance_policy_rule} and the company-scoped {@code category} this
     * class creates point at {@code company}, so anything left behind makes the
     * next suite's {@code companyRepository.deleteAll()} fail on a
     * referential-integrity violation - in its {@code setUp}, where the cause
     * is least obvious. The suites share one Spring context and one H2
     * instance, so cleaning up after this class is this class's job rather than
     * something thirty other files should have to learn about.
     */
    @AfterEach
    void tearDown() {
        policyApplicationRepository.deleteAll();
        policyOutcomeRepository.deleteAll();
        policyRuleRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        summaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        categoryRepository.deleteAll();
        companyRepository.deleteAll();
    }

    @BeforeEach
    void setUp() {
        policyApplicationRepository.deleteAll();
        policyOutcomeRepository.deleteAll();
        policyRuleRepository.deleteAll();
        dailyAttendanceRepository.deleteAll();
        summaryRepository.deleteAll();
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
        employeeRepository.deleteAll();
        shiftRepository.deleteAll();
        categoryRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        holidayRepository.deleteAll();
        companyRepository.deleteAll();

        company = companyRepository.save(Company.builder()
                .companyCode("POLICY-CO").companyName("Policy Co").status(RecordStatus.ACTIVE).build());

        shift = shiftRepository.save(Shift.builder()
                .company(company).shiftCode("GENERAL").shiftName("General")
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0))
                .workingHours(8).breakMinutes(60).graceMinutes(0).overtimeWindowMinutes(240)
                .build());

        Category staff = categoryRepository.save(Category.builder()
                .company(company).categoryCode("STAFF").categoryName("Staff").build());

        saveEmployee("HR001", Role.HR, null);
        saveEmployee("SE10012", Role.EMPLOYEE, staff);

        // Four working days. Day 3 is one minute past a 15-minute grace; the
        // others are comfortably on time.
        rosterAndPunch(PERIOD.atDay(1), "09:14", "18:05");
        rosterAndPunch(PERIOD.atDay(2), "09:15", "18:05");
        rosterAndPunch(PERIOD.atDay(3), "09:16", "18:30");
        rosterAndPunch(PERIOD.atDay(4), "09:22", "18:30");
    }

    // ---- the guarantee the whole feature rests on --------------------------

    @Test
    @DisplayName("a company with no policy rules generates exactly what it did before this feature existed - four full days, no LOP, no trace")
    void noRulesConfiguredChangesNothing() {
        generate();

        assertThat(statuses().values()).containsOnly(AttendanceStatus.PRESENT);

        MonthlyAttendanceSummary summary = summary();
        assertThat(summary.getWorkingDays()).isEqualTo(4);
        assertThat(summary.getPresentDays()).isEqualByComparingTo("4.0");
        assertThat(summary.getLopDays()).isEqualByComparingTo("0.0");
        assertThat(summary.getPolicyLopDays()).isEqualByComparingTo("0.0");
        assertThat(summary.getCompOffCreditDays()).isEqualByComparingTo("0.0");

        // Not one trace row: with no rules there is nothing to explain.
        assertThat(policyApplicationRepository.findAll()).isEmpty();
    }

    // ---- scenario A --------------------------------------------------------

    @Test
    @DisplayName("scenario A: a 15-minute grace at CATEGORY=STAFF turns days 3 and 4 into half days and moves LOP from 0 to 1")
    void lateArrivalRuleMovesTheMonth() {
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_ARRIVAL, "2026-09-01", true,
                "{\"graceMinutes\":15,\"penaltyStatus\":\"HALF_DAY\"}");

        generate();

        Map<LocalDate, AttendanceStatus> statuses = statuses();
        assertThat(statuses.get(PERIOD.atDay(1))).isEqualTo(AttendanceStatus.PRESENT);
        // 09:15 exactly is not late - the grace boundary is inclusive.
        assertThat(statuses.get(PERIOD.atDay(2))).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(statuses.get(PERIOD.atDay(3))).isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(statuses.get(PERIOD.atDay(4))).isEqualTo(AttendanceStatus.HALF_DAY);

        MonthlyAttendanceSummary summary = summary();
        assertThat(summary.getPresentDays()).isEqualByComparingTo("3.0");
        assertThat(summary.getLopDays()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("every penalised day carries a trace naming the rule, the version, the scope and the minutes it used")
    void everyPenalisedDayIsExplained() {
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_ARRIVAL, "2026-09-01", true,
                "{\"graceMinutes\":15,\"penaltyStatus\":\"HALF_DAY\"}");

        generate();

        assertThat(policyApplicationRepository
                .findAllByUserIdAndAttendanceDate("SE10012", PERIOD.atDay(3)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getRuleType()).isEqualTo(RuleType.LATE_ARRIVAL);
                    assertThat(row.getStatusBefore()).isEqualTo(AttendanceStatus.PRESENT);
                    assertThat(row.getStatusAfter()).isEqualTo(AttendanceStatus.HALF_DAY);
                    assertThat(row.getExplanation())
                            .contains("in 09:16")
                            .contains("1 min beyond a 15 min grace")
                            .contains("LATE_ARRIVAL v1 scoped CATEGORY=STAFF");
                });

        // A day the rule did not change leaves no row - the trace answers "why
        // is this day what it is", and a rule that changed nothing is not part
        // of the answer.
        assertThat(policyApplicationRepository
                .findAllByUserIdAndAttendanceDate("SE10012", PERIOD.atDay(1))).isEmpty();
    }

    // ---- effective dating --------------------------------------------------

    @Test
    @DisplayName("a rule effective mid-month prices only the days on and after it, so days before it keep the numbers they already had")
    void effectiveFromAppliesPerDayNotPerMonth() {
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_ARRIVAL, "2026-09-03", true,
                "{\"graceMinutes\":15,\"penaltyStatus\":\"HALF_DAY\"}");

        generate();

        Map<LocalDate, AttendanceStatus> statuses = statuses();
        // Day 4 would have been late under the rule, but the rule did not exist
        // on day 1 or 2 - and days 3 and 4 are on/after it.
        assertThat(statuses.get(PERIOD.atDay(1))).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(statuses.get(PERIOD.atDay(2))).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(statuses.get(PERIOD.atDay(3))).isEqualTo(AttendanceStatus.HALF_DAY);
        assertThat(statuses.get(PERIOD.atDay(4))).isEqualTo(AttendanceStatus.HALF_DAY);
    }

    @Test
    @DisplayName("a rule dated after the month cannot re-price it - the reason regenerating an old month is safe")
    void aFutureRuleCannotRepriceAPastMonth() {
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_ARRIVAL, "2026-10-01", true,
                "{\"graceMinutes\":15,\"penaltyStatus\":\"HALF_DAY\"}");

        generate();

        assertThat(statuses().values()).containsOnly(AttendanceStatus.PRESENT);
        assertThat(summary().getLopDays()).isEqualByComparingTo("0.0");
    }

    // ---- precedence, through a real run ------------------------------------

    @Test
    @DisplayName("a disabled category rule beats an enabled company rule, so that population keeps today's behaviour")
    void disabledCategoryRuleOptsThePopulationOut() {
        saveRule(RuleScope.COMPANY, RuleScope.ANY, RuleType.LATE_ARRIVAL, "2026-09-01", true,
                "{\"graceMinutes\":5,\"penaltyStatus\":\"HALF_DAY\"}");
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_ARRIVAL, "2026-09-01", false,
                "{\"graceMinutes\":0,\"penaltyStatus\":\"HALF_DAY\"}");

        generate();

        // Under the company rule every one of these days is late. The staff
        // opt-out means none of them is penalised.
        assertThat(statuses().values()).containsOnly(AttendanceStatus.PRESENT);
        assertThat(policyApplicationRepository.findAll()).isEmpty();
    }

    // ---- month-scoped rules, end to end ------------------------------------

    @Test
    @DisplayName("scenario B: the early-exit budget reaches the stored summary as LOP, with the base and the penalty separately visible")
    void earlyExitBudgetReachesTheSummary() {
        // Replace the fixture with four early-exit days: 20 + 25 + 30 exhausts a
        // 60-minute budget on the third, so only the fourth is charged.
        resetDays();
        rosterAndPunch(PERIOD.atDay(1), "09:00", "17:40");   // 20 min early
        rosterAndPunch(PERIOD.atDay(2), "09:00", "17:35");   // 25 min early
        rosterAndPunch(PERIOD.atDay(3), "09:00", "17:30");   // 30 min early
        rosterAndPunch(PERIOD.atDay(4), "09:00", "17:50");   // 10 min early

        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.EARLY_EXIT_BUDGET, "2026-09-01", true,
                "{\"monthlyBudgetMinutes\":60,\"penaltyDaysPerOccurrence\":0.5}");

        generate();

        MonthlyAttendanceSummary summary = summary();
        // Every day is still worked in full - the penalty is not an absence.
        assertThat(summary.getPresentDays()).isEqualByComparingTo("4.0");
        assertThat(summary.getPolicyLopDays()).isEqualByComparingTo("0.5");
        assertThat(summary.getLopDays()).isEqualByComparingTo("0.5");

        assertThat(policyOutcomeRepository.findAllByUserIdAndMonth("SE10012", PERIOD.toString()))
                .singleElement()
                .satisfies(row -> assertThat(row.getExplanation())
                        .contains("60 min monthly budget")
                        .contains("budget exhausted 2026-09-03"));
    }

    @Test
    @DisplayName("scenario C: three late marks become a half day of LOP on the summary")
    void lateMarkAccumulationReachesTheSummary() {
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_MARK_ACCUMULATION, "2026-09-01", true,
                "{\"minimumLateMinutes\":1,\"occurrencesPerPenalty\":3,\"penaltyLopDays\":0.5}");

        generate();

        // The fixture's shift has no grace, so all four days are late.
        MonthlyAttendanceSummary summary = summary();
        assertThat(summary.getLateCount()).isEqualTo(4);
        assertThat(summary.getPolicyLopDays()).isEqualByComparingTo("0.5");
        assertThat(summary.getLopDays()).isEqualByComparingTo("0.5");
        assertThat(summary.getPresentDays()).isEqualByComparingTo("4.0");
    }

    @Test
    @DisplayName("the double-jeopardy guard end to end: with both late rules configured, a day docked once is not also counted as a mark")
    void lateArrivalAndAccumulationDoNotChargeTheSameDayTwice() {
        // A 15-minute grace docks days 3 and 4 (09:16 and 09:22). Those two must
        // not then also count toward the three-marks penalty.
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_ARRIVAL, "2026-09-01", true,
                "{\"graceMinutes\":15,\"penaltyStatus\":\"HALF_DAY\"}");
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_MARK_ACCUMULATION, "2026-09-01", true,
                "{\"minimumLateMinutes\":1,\"occurrencesPerPenalty\":3,\"penaltyLopDays\":0.5}");

        generate();

        MonthlyAttendanceSummary summary = summary();
        // Days 1 and 2 are late against the shift's own zero grace but were not
        // docked, so they are the only two marks - short of the three needed.
        assertThat(summary.getPolicyLopDays()).isEqualByComparingTo("0.0");
        // The whole LOP is the two half days LATE_ARRIVAL took, and nothing more.
        assertThat(summary.getPresentDays()).isEqualByComparingTo("3.0");
        assertThat(summary.getLopDays()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("policy LOP can never push total LOP past the days the employee was expected to work")
    void policyLopIsClampedToWorkingDays() {
        // A full day of LOP per late mark, on four late days: 4 marks / 1 per
        // penalty = 4.0 policy LOP against 4 working days that were all worked.
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_MARK_ACCUMULATION, "2026-09-01", true,
                "{\"minimumLateMinutes\":1,\"occurrencesPerPenalty\":1,\"penaltyLopDays\":1.0}");

        generate();

        MonthlyAttendanceSummary summary = summary();
        assertThat(summary.getWorkingDays()).isEqualTo(4);
        assertThat(summary.getPolicyLopDays()).isEqualByComparingTo("4.0");
        assertThat(summary.getLopDays()).isEqualByComparingTo("4.0");
        // Not 8.0. Without the clamp the base LOP would have added on top.
        assertThat(summary.getLopDays()).isLessThanOrEqualTo(new java.math.BigDecimal("4.0"));
    }

    @Test
    @DisplayName("month outcomes are replaced on every rebuild, never appended - three runs leave one row")
    void monthOutcomesAreReplacedNotAccumulated() {
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_MARK_ACCUMULATION, "2026-09-01", true,
                "{\"minimumLateMinutes\":1,\"occurrencesPerPenalty\":3,\"penaltyLopDays\":0.5}");

        generate();
        generate();
        generate();

        assertThat(policyOutcomeRepository.findAllByUserIdAndMonth("SE10012", PERIOD.toString()))
                .hasSize(1);
        assertThat(summary().getPolicyLopDays()).isEqualByComparingTo("0.5");
    }

    // ---- idempotency -------------------------------------------------------

    @Test
    @DisplayName("generating repeatedly is idempotent: the same statuses, the same LOP, and one trace row per rule - not one per run")
    void regenerationIsIdempotentAndDoesNotDuplicateTheTrace() {
        saveRule(RuleScope.CATEGORY, "STAFF", RuleType.LATE_ARRIVAL, "2026-09-01", true,
                "{\"graceMinutes\":15,\"penaltyStatus\":\"HALF_DAY\"}");

        generate();
        Map<LocalDate, AttendanceStatus> first = statuses();
        java.math.BigDecimal firstLop = summary().getLopDays();

        generate();
        generate();

        assertThat(statuses()).isEqualTo(first);
        assertThat(summary().getLopDays()).isEqualByComparingTo(firstLop);
        // Three runs, still one row per penalised day - the trace is replaced,
        // never appended to.
        assertThat(policyApplicationRepository
                .findAllByUserIdAndAttendanceDate("SE10012", PERIOD.atDay(3))).hasSize(1);
    }

    // ---- helpers -----------------------------------------------------------

    private void generate() {
        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(PERIOD);
        request.setUserIds(List.of("SE10012"));
        request.setGeneratedBy("HR001");
        attendanceService.generate(request);
    }

    private Map<LocalDate, AttendanceStatus> statuses() {
        return dailyAttendanceRepository
                .findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        "SE10012", PERIOD.atDay(1), PERIOD.atEndOfMonth())
                .stream()
                .collect(Collectors.toMap(DailyAttendance::getAttendanceDate, DailyAttendance::getStatus));
    }

    private MonthlyAttendanceSummary summary() {
        return summaryRepository.findByUserIdAndMonth("SE10012", PERIOD.toString()).orElseThrow();
    }

    /** Clears the fixture's days so a test can lay down its own punch pattern. */
    private void resetDays() {
        deviceLogRepository.deleteAll();
        shiftScheduleRepository.deleteAll();
    }

    private void rosterAndPunch(LocalDate date, String in, String out) {
        shiftScheduleRepository.save(ShiftSchedule.builder()
                .userId("SE10012").shiftDate(date).shift(shift).weekOff(false).build());
        punch(date.atTime(LocalTime.parse(in)));
        punch(date.atTime(LocalTime.parse(out)));
    }

    private void punch(LocalDateTime at) {
        deviceLogRepository.save(DeviceLog.builder()
                .deviceLogId(punchId++).deviceId(1L).userId("SE10012").logDate(at).build());
    }

    private AttendancePolicyRule saveRule(RuleScope scope, String scopeRef, RuleType ruleType,
                                          String effectiveFrom, boolean enabled, String params) {
        return policyRuleRepository.save(AttendancePolicyRule.builder()
                .company(company).scope(scope).scopeRef(scopeRef).ruleType(ruleType)
                .version(1).effectiveFrom(LocalDate.parse(effectiveFrom)).enabled(enabled)
                .params(params).createdAt(Instant.now()).createdBy("HR001")
                .build());
    }

    private Employee saveEmployee(String userId, Role role, Category category) {
        return employeeRepository.save(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company).category(category)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .overtimeEligible(false)
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }
}
