package com.accusharp.hrms.leave;

import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveHrDirectRequest;
import com.accusharp.hrms.dto.LeaveResponse;
import com.accusharp.hrms.dto.LeaveYearCloseRow;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.entity.MonthlyAttendanceSummary;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.ExcessHandling;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.repository.LeaveRuleRepository;
import com.accusharp.hrms.service.leave.LeaveAccrualService;
import com.accusharp.hrms.service.leave.LeaveBalanceService;
import com.accusharp.hrms.service.leave.LeaveService;
import com.accusharp.hrms.service.leave.LeaveYearCloseService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A company whose leave year is the financial year, April to March.
 *
 * <p>Every place leave used to assume a calendar year follows the company's own
 * year instead: which balance a leave comes out of, which balance it goes back
 * to, where a month's earned leave lands, how a joiner is pro-rated, and when
 * the year closes. Leave year 2026 here is April 2026 to March 2027.
 *
 * <p>The last test is the company HR described: only earned leave carries
 * forward, at most 45 days, and anything above 45 lapses.
 */
@SpringBootTest
class FinancialLeaveYearTest {

    private static final String HR = "FY001";
    private static final String EMPLOYEE = "FY002";
    private static final LocalDate FY_2026_START = LocalDate.of(2026, 4, 1);

    @Autowired private LeaveService leaveService;
    @Autowired private LeaveBalanceService leaveBalanceService;
    @Autowired private LeaveAccrualService accrualService;
    @Autowired private LeaveYearCloseService yearCloseService;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private LeaveRuleRepository leaveRuleRepository;
    @Autowired private LeaveRequestRepository leaveRequestRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CompanyRepository companyRepository;

    private Company company;
    private Employee employee;

    @BeforeEach
    void setUp() {
        clean();
        company = companyRepository.save(Company.builder()
                .companyCode("FY-CO").companyName("Financial Year Co").status(RecordStatus.ACTIVE)
                .leaveYearStartMonth(4)
                .build());
        saveEmployee(HR, Role.HR, LocalDate.of(2022, 1, 1));
        employee = saveEmployee(EMPLOYEE, Role.EMPLOYEE, LocalDate.of(2022, 1, 1));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ---- applying and cancelling -------------------------------------------

    @Test
    @DisplayName("leave taken in February comes out of the year that started last April")
    void februaryUsesTheYearThatStartedInApril() {
        hrDirect(LeaveType.CASUAL_LEAVE, LocalDate.of(2027, 2, 10), LocalDate.of(2027, 2, 11));

        assertThat(used(2026, LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("a leave cannot run across the end of March, the end of the leave year")
    void aLeaveCannotCrossTheYearEnd() {
        assertThatThrownBy(() ->
                hrDirect(LeaveType.CASUAL_LEAVE, LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("two leave years");
    }

    @Test
    @DisplayName("New Year's week is the middle of a financial year - one leave, one balance")
    void newYearIsMidYear() {
        hrDirect(LeaveType.CASUAL_LEAVE, LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1));

        assertThat(used(2026, LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("cancelling gives the days back to the year they came out of")
    void cancellingRestoresTheSameYear() {
        LeaveResponse taken = hrDirect(LeaveType.CASUAL_LEAVE, LocalDate.of(2027, 2, 10), LocalDate.of(2027, 2, 11));

        LeaveDecisionRequest decision = new LeaveDecisionRequest();
        decision.setApproverId(HR);
        decision.setComments("plans changed");
        leaveService.cancel(taken.id(), decision);

        assertThat(used(2026, LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("0");
    }

    // ---- earned leave and grants -------------------------------------------

    @Test
    @DisplayName("March's earned leave counts to the year ending in March; April's starts the next")
    void earnedLeaveLandsInTheFinancialYear() {
        rule(LeaveType.EARNED_LEAVE, LeaveGrant.EARNED_BY_ATTENDANCE, null, "45", ExcessHandling.LAPSE, FY_2026_START);

        accrue(YearMonth.of(2027, 3));
        accrue(YearMonth.of(2027, 4));

        assertThat(quota(EMPLOYEE, 2026, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("1.5");
        assertThat(quota(EMPLOYEE, 2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("someone joining in June gets ten months of the year - June to March")
    void juneJoinerIsProRatedToTheFinancialYear() {
        rule(LeaveType.CASUAL_LEAVE, LeaveGrant.YEARLY_GRANT, "12", null, null, FY_2026_START);
        saveEmployee("FY003", Role.EMPLOYEE, LocalDate.of(2026, 6, 1));

        assertThat(quota("FY003", 2026, LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("10");
    }

    // ---- closing the year --------------------------------------------------

    @Test
    @DisplayName("the year closes at the end of March, so a rule starting in January applies to it")
    void theYearClosesAtTheEndOfMarch() {
        // In effect on 31 March 2027, when this company's 2026 year ends - though
        // not on 31 December 2026, when a calendar company's would.
        rule(LeaveType.EARNED_LEAVE, LeaveGrant.EARNED_BY_ATTENDANCE, null, "30", null, LocalDate.of(2027, 1, 1));
        balance(2026, LeaveType.EARNED_LEAVE, "12", "0");

        yearCloseService.closeYear(2026, HR);

        assertThat(quota(EMPLOYEE, 2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("HR's example company: only EL carries forward, at most 45, and the rest lapses")
    void theExampleCompany() {
        rule(LeaveType.EARNED_LEAVE, LeaveGrant.EARNED_BY_ATTENDANCE, null, "45", ExcessHandling.LAPSE, FY_2026_START);
        rule(LeaveType.CASUAL_LEAVE, LeaveGrant.YEARLY_GRANT, "8", null, null, FY_2026_START);
        rule(LeaveType.SICK_LEAVE, LeaveGrant.YEARLY_GRANT, "7", null, null, FY_2026_START);
        balance(2026, LeaveType.EARNED_LEAVE, "50", "0");
        balance(2026, LeaveType.CASUAL_LEAVE, "8", "3");
        balance(2026, LeaveType.SICK_LEAVE, "7", "4");

        List<LeaveYearCloseRow> rows = yearCloseService.closeYear(2026, HR);

        // EL: 45 carried, 5 lapsed - not listed for payout.
        assertThat(quota(EMPLOYEE, 2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("45");
        assertThat(rows).filteredOn(row -> row.userId().equals(EMPLOYEE))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.leaveType()).isEqualTo(LeaveType.EARNED_LEAVE);
                    assertThat(row.carriedForward()).isEqualByComparingTo("45");
                    assertThat(row.lapsed()).isEqualByComparingTo("5");
                    assertThat(row.excessToPayOut()).isEqualByComparingTo("0");
                });
        // CL and SL: nothing carries; next year opens at its own grant.
        assertThat(quota(EMPLOYEE, 2027, LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("8");
        assertThat(quota(EMPLOYEE, 2027, LeaveType.SICK_LEAVE)).isEqualByComparingTo("7");
    }

    // ---- helpers -----------------------------------------------------------

    private LeaveResponse hrDirect(LeaveType type, LocalDate from, LocalDate to) {
        LeaveHrDirectRequest request = new LeaveHrDirectRequest();
        request.setUserId(EMPLOYEE);
        request.setLeaveType(type);
        request.setFromDate(from);
        request.setToDate(to);
        request.setDuration(LeaveDuration.FULL_DAY);
        request.setReason("financial year test");
        request.setApproverId(HR);
        return leaveService.hrDirectCreate(request);
    }

    private void accrue(YearMonth month) {
        accrualService.accrueMonth(employee, month, MonthlyAttendanceSummary.builder()
                .userId(EMPLOYEE).month(month.toString())
                .workingDays(26).lopDays(BigDecimal.ZERO)
                .build());
    }

    private BigDecimal used(int leaveYear, LeaveType type) {
        return leaveBalanceService.getOrCreate(EMPLOYEE, leaveYear, type).getUsed();
    }

    private BigDecimal quota(String userId, int leaveYear, LeaveType type) {
        return leaveBalanceService.getOrCreate(userId, leaveYear, type).getQuota();
    }

    private void balance(int leaveYear, LeaveType type, String quota, String used) {
        leaveBalanceRepository.save(LeaveBalance.builder()
                .userId(EMPLOYEE).leaveYear(leaveYear).leaveType(type)
                .quota(new BigDecimal(quota)).used(new BigDecimal(used)).build());
    }

    private void rule(LeaveType type, LeaveGrant grant, String yearlyDays, String cap,
                      ExcessHandling excess, LocalDate effectiveFrom) {
        leaveRuleRepository.save(LeaveRule.builder()
                .company(company).scope(RuleScope.COMPANY).scopeRef(LeaveRule.ANY)
                .leaveType(type).grantMethod(grant)
                .yearlyDays(yearlyDays == null ? null : new BigDecimal(yearlyDays))
                .carryForwardCap(cap == null ? null : new BigDecimal(cap))
                .excessOverCap(excess)
                .effectiveFrom(effectiveFrom).enabled(true)
                .build());
    }

    private Employee saveEmployee(String userId, Role role, LocalDate joined) {
        return employeeRepository.saveAndFlush(Employee.builder()
                .userId(userId).employeeCode("EMP-" + userId).employeeName(userId)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(joined)
                .overtimeEligible(false)
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
    }

    private void clean() {
        leaveCreditRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        leaveRequestRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();
    }
}
