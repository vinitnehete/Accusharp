package com.accusharp.hrms.leave;

import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.entity.LeaveCredit;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.LeaveCreditKind;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.LeaveRuleScope;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.repository.LeaveRuleRepository;
import com.accusharp.hrms.service.leave.EarnedLeaveAccrualService;
import com.accusharp.hrms.service.leave.LeaveBalanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Posting a month's earned leave onto the balance.
 *
 * <p>Two properties matter more than the arithmetic (which
 * {@code EarnedLeaveCalculatorTest} covers):
 *
 * <ul>
 *   <li><b>It adds to what is already there.</b> Opening balances are typed into
 *       the table by hand; a credit goes on top of them, never replaces them.</li>
 *   <li><b>Posting the same month twice credits it once.</b> It runs when
 *       payroll is generated, and payroll can be regenerated. Each month has one
 *       ledger entry; a repost replaces the entry and moves the balance by the
 *       difference - so a regeneration after a late correction fixes the credit
 *       instead of stacking a second one.</li>
 * </ul>
 *
 * <p>The go-live month is simply the EL rule's effective date. No rule, no
 * accrual; months before it are never credited.
 */
@SpringBootTest
class EarnedLeaveAccrualTest {

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final LocalDate GO_LIVE = LocalDate.of(2026, 9, 1);
    private static final String EMPLOYEE = "EA001";

    @Autowired private EarnedLeaveAccrualService accrualService;
    @Autowired private LeaveBalanceService leaveBalanceService;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private LeaveRuleRepository leaveRuleRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CompanyRepository companyRepository;

    private Company company;
    private Employee employee;

    @BeforeEach
    void setUp() {
        clean();
        company = companyRepository.save(Company.builder()
                .companyCode("ACCRUE-CO").companyName("Accrual Co").status(RecordStatus.ACTIVE).build());
        employee = saveEmployee(EMPLOYEE, LocalDate.of(2022, 1, 1));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- switching it on ---------------------------------------------------

    @Test
    @DisplayName("with no EL rule, nothing is credited and nothing is recorded")
    void noRuleNoCredit() {
        assertThat(accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "0"))).isEqualByComparingTo("0");

        assertThat(leaveCreditRepository.findAll()).isEmpty();
        assertThat(elQuota()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("months before the go-live month are never credited")
    void monthsBeforeGoLiveAreNeverCredited() {
        elRule(GO_LIVE);

        assertThat(accrualService.accrueMonth(employee, AUGUST, summary(26, "0"))).isEqualByComparingTo("0");
        assertThat(accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "0"))).isEqualByComparingTo("1.5");
        assertThat(elQuota()).isEqualByComparingTo("1.5");
    }

    // ---- the credit --------------------------------------------------------

    @Test
    @DisplayName("a full month is credited and recorded with the reason for it")
    void fullMonthIsCreditedAndRecorded() {
        elRule(GO_LIVE);

        accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "0"));

        List<LeaveCredit> credits = leaveCreditRepository.findAllByUserIdAndLeaveYearOrderByPeriodAsc(EMPLOYEE, 2026);
        assertThat(credits).singleElement().satisfies(credit -> {
            assertThat(credit.getKind()).isEqualTo(LeaveCreditKind.MONTHLY_ACCRUAL);
            assertThat(credit.getLeaveType()).isEqualTo(LeaveType.EARNED_LEAVE);
            assertThat(credit.getPeriod()).isEqualTo("2026-09");
            assertThat(credit.getDays()).isEqualByComparingTo("1.5");
            assertThat(credit.getDaysCounted()).isEqualByComparingTo("26");
            assertThat(credit.getBasis()).contains("full month");
        });
    }

    @Test
    @DisplayName("the credit goes on top of an opening balance entered by hand")
    void addsToTheBalanceEnteredByHand() {
        elRule(GO_LIVE);
        leaveBalanceRepository.save(LeaveBalance.builder()
                .userId(EMPLOYEE).leaveYear(2026).leaveType(LeaveType.EARNED_LEAVE)
                .quota(new BigDecimal("10")).used(BigDecimal.ZERO).build());

        accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "0"));

        assertThat(elQuota()).isEqualByComparingTo("11.5");
    }

    @Test
    @DisplayName("posting the same month twice credits it once")
    void samMonthTwiceCreditsOnce() {
        elRule(GO_LIVE);

        accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "0"));
        accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "0"));

        assertThat(elQuota()).isEqualByComparingTo("1.5");
        assertThat(leaveCreditRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("regenerating after a correction adds LOP: the credit is recomputed, not stacked")
    void regenerationAdjustsTheCredit() {
        elRule(GO_LIVE);
        accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "0"));

        // Payroll regenerated after two days turned out to be LOP: 24 counted.
        accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "2"));

        assertThat(elQuota()).isEqualByComparingTo("1.2");
        assertThat(leaveCreditRepository.findAll()).singleElement()
                .satisfies(credit -> assertThat(credit.getDays()).isEqualByComparingTo("1.2"));
    }

    @Test
    @DisplayName("earned leave is usable as soon as it is credited")
    void usableAsSoonAsCredited() {
        elRule(GO_LIVE);
        accrualService.accrueMonth(employee, SEPTEMBER, summary(26, "0"));

        leaveBalanceService.consume(EMPLOYEE, 2026, LeaveType.EARNED_LEAVE, BigDecimal.ONE);

        assertThat(leaveBalanceService.getOrCreate(EMPLOYEE, 2026, LeaveType.EARNED_LEAVE).available())
                .isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("someone who joined mid-month is credited for the days they were there, not a full month")
    void midMonthJoinerGetsNoFullMonth() {
        elRule(LocalDate.of(2026, 1, 1));
        Employee joiner = saveEmployee("EA002", LocalDate.of(2026, 9, 15));

        // 13 working days after joining, no LOP: 13 / 20 = 0.65 -> 0.7.
        assertThat(accrualService.accrueMonth(joiner, SEPTEMBER, summary(13, "0"))).isEqualByComparingTo("0.7");
    }

    @Test
    @DisplayName("a contractor's worker earns nothing")
    void contractorWorkerEarnsNothing() {
        elRule(LocalDate.of(2026, 1, 1));
        Employee worker = Employee.builder()
                .userId("EA003").status(EmployeeStatus.PERMANENT)
                .contractor(Contractor.builder().id(1L).build())
                .build();

        assertThat(accrualService.accrueMonth(worker, SEPTEMBER, summary(26, "0"))).isEqualByComparingTo("0");
        assertThat(leaveCreditRepository.findAll()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------

    private BigDecimal elQuota() {
        return leaveBalanceService.getOrCreate(EMPLOYEE, 2026, LeaveType.EARNED_LEAVE).getQuota();
    }

    private MonthlyAttendanceSummary summary(long workingDays, String lopDays) {
        return MonthlyAttendanceSummary.builder()
                .userId(EMPLOYEE).month(SEPTEMBER.toString())
                .workingDays(workingDays).lopDays(new BigDecimal(lopDays))
                .build();
    }

    private void elRule(LocalDate effectiveFrom) {
        leaveRuleRepository.save(LeaveRule.builder()
                .company(company).scope(LeaveRuleScope.COMPANY).scopeRef(LeaveRule.ANY)
                .leaveType(LeaveType.EARNED_LEAVE).grantMethod(LeaveGrant.EARNED_BY_ATTENDANCE)
                .carryForwardCap(new BigDecimal("30"))
                .effectiveFrom(effectiveFrom).enabled(true)
                .build());
    }

    private Employee saveEmployee(String userId, LocalDate joined) {
        return employeeRepository.saveAndFlush(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .joiningDate(joined)
                .overtimeEligible(false)
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }

    private void clean() {
        leaveCreditRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();
    }
}
