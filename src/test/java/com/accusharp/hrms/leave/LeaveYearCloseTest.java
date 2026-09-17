package com.accusharp.hrms.leave;

import com.accusharp.hrms.dto.LeaveYearCloseRow;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveBalance;
import com.accusharp.hrms.entity.LeaveCredit;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.ExcessHandling;
import com.accusharp.hrms.enums.LeaveCreditKind;
import com.accusharp.hrms.enums.LeaveGrant;
import com.accusharp.hrms.enums.RuleScope;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.LeaveBalanceRepository;
import com.accusharp.hrms.repository.LeaveCreditRepository;
import com.accusharp.hrms.repository.LeaveRuleRepository;
import com.accusharp.hrms.service.leave.LeaveBalanceService;
import com.accusharp.hrms.service.leave.LeaveYearCloseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing a leave year: what carries into the next one.
 *
 * <p>EL carries forward up to its rule's cap (30, the OSH Code limit), and
 * anything above the cap is reported for payout. CL and SL lapse. Leave with no
 * cap configured lapses too - which is what every balance did before this.
 *
 * <p>An explicit action, run once December's payroll is done, rather than an
 * automatic rollover on 1 January: December's EL is credited when December
 * payroll runs, which is in January, so a rollover on the 1st would carry the
 * wrong figure. Running it again is safe - it replaces its own carry-forward
 * rather than adding a second one - which is what makes it correct to rerun
 * after a late December correction.
 */
@SpringBootTest
class LeaveYearCloseTest {

    private static final String EMPLOYEE = "YC001";

    @Autowired private LeaveYearCloseService yearCloseService;
    @Autowired private LeaveBalanceService leaveBalanceService;
    @Autowired private LeaveBalanceRepository leaveBalanceRepository;
    @Autowired private LeaveCreditRepository leaveCreditRepository;
    @Autowired private LeaveRuleRepository leaveRuleRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private CompanyRepository companyRepository;

    private Company company;

    @BeforeEach
    void setUp() {
        clean();
        company = companyRepository.save(Company.builder()
                .companyCode("CLOSE-CO").companyName("Close Co").status(RecordStatus.ACTIVE).build());
        employeeRepository.saveAndFlush(Employee.builder()
                .userId(EMPLOYEE).employeeCode("EMP-" + EMPLOYEE).employeeName(EMPLOYEE)
                .company(company)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .overtimeEligible(false)
                .accountEnabled(true).accountLocked(false).failedLoginAttempts(0)
                .build());
        leaveRuleRepository.save(LeaveRule.builder()
                .company(company).scope(RuleScope.COMPANY).scopeRef(LeaveRule.ANY)
                .leaveType(LeaveType.EARNED_LEAVE).grantMethod(LeaveGrant.EARNED_BY_ATTENDANCE)
                .carryForwardCap(new BigDecimal("30"))
                .effectiveFrom(LocalDate.of(2026, 1, 1)).enabled(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    @DisplayName("EL above the cap carries 30 and reports the rest for payout")
    void aboveTheCapCarriesThirtyAndReportsTheExcess() {
        balance(2026, LeaveType.EARNED_LEAVE, "40", "6");   // 34 available

        List<LeaveYearCloseRow> rows = yearCloseService.closeYear(2026, "HR001");

        assertThat(quota(2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("30");
        assertThat(rowFor(rows, LeaveType.EARNED_LEAVE)).satisfies(row -> {
            assertThat(row.available()).isEqualByComparingTo("34");
            assertThat(row.carriedForward()).isEqualByComparingTo("30");
            assertThat(row.excessToPayOut()).isEqualByComparingTo("4");
            assertThat(row.lapsed()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("EL under the cap carries in full")
    void underTheCapCarriesEverything() {
        balance(2026, LeaveType.EARNED_LEAVE, "12", "0");

        List<LeaveYearCloseRow> rows = yearCloseService.closeYear(2026, "HR001");

        assertThat(quota(2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("12");
        assertThat(rowFor(rows, LeaveType.EARNED_LEAVE).excessToPayOut()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CL and SL lapse - next year starts from its own grant")
    void casualAndSickLeaveLapse() {
        balance(2026, LeaveType.CASUAL_LEAVE, "12", "2");   // 10 unused

        yearCloseService.closeYear(2026, "HR001");

        // No rule configures a cap for CL, so nothing carries - exactly as before.
        assertThat(quota(2027, LeaveType.CASUAL_LEAVE)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("closing again after a late December credit adjusts the carry-forward instead of doubling it")
    void rerunAdjustsNotDoubles() {
        balance(2026, LeaveType.EARNED_LEAVE, "12", "0");
        yearCloseService.closeYear(2026, "HR001");
        assertThat(quota(2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("12");

        // December payroll ran in January and credited 1.5 more to 2026.
        LeaveBalance december = leaveBalanceService.getOrCreate(EMPLOYEE, 2026, LeaveType.EARNED_LEAVE);
        december.setQuota(december.getQuota().add(new BigDecimal("1.5")));
        leaveBalanceRepository.save(december);

        yearCloseService.closeYear(2026, "HR001");

        assertThat(quota(2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("13.5");
        assertThat(leaveCreditRepository.findAll())
                .filteredOn(credit -> credit.getKind() == LeaveCreditKind.CARRY_FORWARD)
                .singleElement()
                .satisfies(credit -> {
                    assertThat(credit.getPeriod()).isEqualTo("2026");
                    assertThat(credit.getLeaveYear()).isEqualTo(2027);
                    assertThat(credit.getDays()).isEqualByComparingTo("13.5");
                });
    }

    @Test
    @DisplayName("the carry-forward lands on next year's balance even if it was already opened in January")
    void carriesIntoABalanceAlreadyOpened() {
        balance(2026, LeaveType.EARNED_LEAVE, "12", "0");
        // Someone looked at their 2027 balances before HR closed 2026.
        leaveBalanceService.getOrCreate(EMPLOYEE, 2027, LeaveType.EARNED_LEAVE);

        yearCloseService.closeYear(2026, "HR001");

        assertThat(quota(2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("an overdrawn balance carries nothing")
    void overdrawnCarriesNothing() {
        balance(2026, LeaveType.EARNED_LEAVE, "1", "2");

        yearCloseService.closeYear(2026, "HR001");

        assertThat(quota(2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a rule set to lapse drops the excess instead of listing it for payout")
    void lapseDropsTheExcess() {
        LeaveRule rule = leaveRuleRepository.findAll().getFirst();
        rule.setCarryForwardCap(new BigDecimal("45"));
        rule.setExcessOverCap(ExcessHandling.LAPSE);
        leaveRuleRepository.save(rule);
        balance(2026, LeaveType.EARNED_LEAVE, "50", "0");

        List<LeaveYearCloseRow> rows = yearCloseService.closeYear(2026, "HR001");

        assertThat(quota(2027, LeaveType.EARNED_LEAVE)).isEqualByComparingTo("45");
        assertThat(rowFor(rows, LeaveType.EARNED_LEAVE)).satisfies(row -> {
            assertThat(row.carriedForward()).isEqualByComparingTo("45");
            assertThat(row.lapsed()).isEqualByComparingTo("5");
            assertThat(row.excessToPayOut()).isEqualByComparingTo("0");
        });
        assertThat(leaveCreditRepository.findAll())
                .filteredOn(credit -> credit.getKind() == LeaveCreditKind.CARRY_FORWARD)
                .singleElement()
                .satisfies(credit -> {
                    assertThat(credit.getLapsedDays()).isEqualByComparingTo("5");
                    assertThat(credit.getExcessDays()).isEqualByComparingTo("0");
                });
    }

    // ---- helpers -----------------------------------------------------------

    private LeaveYearCloseRow rowFor(List<LeaveYearCloseRow> rows, LeaveType type) {
        return rows.stream()
                .filter(row -> row.userId().equals(EMPLOYEE) && row.leaveType() == type)
                .findFirst().orElseThrow();
    }

    private BigDecimal quota(int year, LeaveType type) {
        return leaveBalanceService.getOrCreate(EMPLOYEE, year, type).getQuota();
    }

    private void balance(int year, LeaveType type, String quota, String used) {
        leaveBalanceRepository.save(LeaveBalance.builder()
                .userId(EMPLOYEE).leaveYear(year).leaveType(type)
                .quota(new BigDecimal(quota)).used(new BigDecimal(used)).build());
    }

    private void clean() {
        leaveCreditRepository.deleteAll();
        leaveBalanceRepository.deleteAll();
        leaveRuleRepository.deleteAll();
        employeeRepository.deleteAll();
        companyRepository.deleteAll();
    }
}
