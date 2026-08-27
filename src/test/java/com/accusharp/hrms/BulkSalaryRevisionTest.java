package com.accusharp.hrms;

import com.accusharp.hrms.dto.BulkSalaryRevisionRow;
import com.accusharp.hrms.dto.SalaryRevisionPreview;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.enums.SalaryRevisionReason;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.EmployeeRepository;
import com.accusharp.hrms.repository.SalaryRevisionRepository;
import com.accusharp.hrms.repository.SalaryStructureRevisionRepository;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.SalaryRuleService;
import com.accusharp.hrms.service.calculation.SalaryCalculationService;
import com.accusharp.hrms.util.ParsedCsvRow;
import com.accusharp.hrms.util.SalaryRevisionCsvParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bulk salary revision - a whole appraisal cycle in one file.
 *
 * <p>These rows are not merely an audit trail: payroll reconstructs which gross
 * salary applied on which day from them, so the two guards below are about pay
 * rather than tidiness. A revision may not take effect before the current month
 * - a raise decided in August is earned from August, never backwards into a
 * month already worked and paid - and no employee may hold two revisions on one
 * effective date, which is what a re-uploaded file would otherwise produce.
 */
@SpringBootTest
class BulkSalaryRevisionTest {

    private static final String HR = "HR800";
    private static final String EMP_A = "EMP801";
    private static final String EMP_B = "EMP802";

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private SalaryRevisionRepository salaryRevisionRepository;
    @Autowired private SalaryStructureRevisionRepository salaryStructureRevisionRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    /** Inside the current month, so it clears the no-prior-month rule wherever this runs. */
    private LocalDate effectiveThisMonth;

    @BeforeEach
    void setUp() {
        salaryRevisionRepository.deleteAll();
        salaryStructureRevisionRepository.deleteAll();
        employeeRepository.deleteAll();
        effectiveThisMonth = YearMonth.now().atDay(1);

        saveEmployee(HR, Role.HR, "30000");
        saveEmployee(EMP_A, Role.EMPLOYEE, "26000");
        saveEmployee(EMP_B, Role.EMPLOYEE, "40000");
    }

    // ---- parsing -----------------------------------------------------------

    @Test
    @DisplayName("a well-formed file parses one revision per row")
    void parsesEveryRow() {
        List<ParsedCsvRow<BulkSalaryRevisionRow>> rows = parse("""
                userId,newGrossSalary,effectiveDate,reason,remarks
                EMP801,30000,%s,ANNUAL_INCREMENT,FY26 appraisal
                EMP802,"48,000",%s,PROMOTION,Promoted to lead
                """.formatted(effectiveThisMonth, effectiveThisMonth));

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(ParsedCsvRow::isOk);
        assertThat(rows.get(0).value().userId()).isEqualTo(EMP_A);
        assertThat(rows.get(0).value().request().getReason()).isEqualTo(SalaryRevisionReason.ANNUAL_INCREMENT);
        // Thousands separators survive a spreadsheet export and must not fail the row.
        assertThat(rows.get(1).value().request().getNewGrossSalary()).isEqualByComparingTo("48000");
    }

    @Test
    @DisplayName("a bad row fails alone and the rest of the file still parses")
    void oneBadRowDoesNotAbortTheFile() {
        List<ParsedCsvRow<BulkSalaryRevisionRow>> rows = parse("""
                userId,newGrossSalary,effectiveDate,reason,remarks
                EMP801,30000,%s,ANNUAL_INCREMENT,ok
                EMP802,not-a-number,%s,PROMOTION,broken
                """.formatted(effectiveThisMonth, effectiveThisMonth));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).isOk()).isTrue();
        assertThat(rows.get(1).isOk()).isFalse();
        assertThat(rows.get(1).error()).contains("newGrossSalary").contains("must be a number");
    }

    @Test
    @DisplayName("an unknown reason names the ones that are accepted")
    void unknownReasonIsExplained() {
        List<ParsedCsvRow<BulkSalaryRevisionRow>> rows = parse("""
                userId,newGrossSalary,effectiveDate,reason,remarks
                EMP801,30000,%s,BIG_RAISE,nope
                """.formatted(effectiveThisMonth));

        assertThat(rows.get(0).isOk()).isFalse();
        assertThat(rows.get(0).error()).contains("ANNUAL_INCREMENT").contains("PROMOTION");
    }

    // ---- the rules ---------------------------------------------------------

    @Test
    @DisplayName("a revision effective before the current month is refused")
    void noBackdatingIntoAPriorMonth() {
        LocalDate lastMonth = YearMonth.now().minusMonths(1).atDay(15);

        assertThatThrownBy(() -> employeeService.reviseSalaryByUserId(
                EMP_A, request("30000", lastMonth), HR, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("before the current month");

        assertThat(salaryRevisionRepository.findByEmployeeIdOrderByEffectiveDateAsc(EMP_A)).isEmpty();
        assertThat(employeeRepository.findByUserId(EMP_A).orElseThrow().getGrossSalary())
                .as("a refused revision must not move the salary either")
                .isEqualByComparingTo("26000");
    }

    @Test
    @DisplayName("a revision effective this month or later is accepted")
    void currentAndFutureMonthsAreAllowed() {
        employeeService.reviseSalaryByUserId(EMP_A, request("30000", effectiveThisMonth), HR, false);
        employeeService.reviseSalaryByUserId(
                EMP_B, request("48000", YearMonth.now().plusMonths(2).atDay(1)), HR, false);

        assertThat(employeeRepository.findByUserId(EMP_A).orElseThrow().getGrossSalary())
                .isEqualByComparingTo("30000");
        assertThat(employeeRepository.findByUserId(EMP_B).orElseThrow().getGrossSalary())
                .isEqualByComparingTo("48000");
    }

    @Test
    @DisplayName("the same employee cannot take two revisions on one effective date")
    void duplicateEffectiveDateIsRefused() {
        employeeService.reviseSalaryByUserId(EMP_A, request("30000", effectiveThisMonth), HR, false);

        assertThatThrownBy(() -> employeeService.reviseSalaryByUserId(
                EMP_A, request("31000", effectiveThisMonth), HR, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already has a salary revision effective");

        assertThat(salaryRevisionRepository.findByEmployeeIdOrderByEffectiveDateAsc(EMP_A)).hasSize(1);
        assertThat(employeeRepository.findByUserId(EMP_A).orElseThrow().getGrossSalary())
                .as("the second attempt must not move the salary again")
                .isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("medical and other allowances are written when the row carries them")
    void allowancesArePersisted() {
        var withAllowances = request("32500", effectiveThisMonth);
        withAllowances.setMedicalAllowance(new BigDecimal("2000"));
        withAllowances.setOtherAllowance(new BigDecimal("1500"));

        employeeService.reviseSalaryByUserId(EMP_A, withAllowances, HR, false);

        Employee after = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(after.getGrossSalary()).isEqualByComparingTo("32500");
        assertThat(after.getMedicalAllowance()).isEqualByComparingTo("2000");
        assertThat(after.getOtherAllowance()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("a revision that omits the allowances leaves them alone")
    void omittedAllowancesAreLeftAsTheyAre() {
        employeeService.reviseSalaryByUserId(EMP_A, request("32500", effectiveThisMonth), HR, false);

        Employee after = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(after.getMedicalAllowance()).isEqualByComparingTo("1250");
        assertThat(after.getOtherAllowance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the report's own column headings are accepted here too")
    void reportHeadingsAreAccepted() {
        List<ParsedCsvRow<BulkSalaryRevisionRow>> rows = parse("""
                userId,newGrossSalary,effectiveDate,reason,Med. Allow,Other Allow
                EMP801,32500,%s,ANNUAL_INCREMENT,2000,1500
                """.formatted(effectiveThisMonth));

        assertThat(rows.get(0).isOk()).as(rows.get(0).error()).isTrue();
        assertThat(rows.get(0).value().request().getMedicalAllowance()).isEqualByComparingTo("2000");
        assertThat(rows.get(0).value().request().getOtherAllowance()).isEqualByComparingTo("1500");
    }

    // ---- dry run -----------------------------------------------------------

    @Test
    @DisplayName("a dry run reports the hike and writes nothing")
    void dryRunCostsTheFileWithoutCommittingIt() {
        SalaryRevisionPreview preview = employeeService.reviseSalaryByUserId(
                EMP_A, request("32500", effectiveThisMonth), HR, true);

        assertThat(preview.applied()).isFalse();
        assertThat(preview.userId()).isEqualTo(EMP_A);
        assertThat(preview.previousGrossSalary()).isEqualByComparingTo("26000");
        assertThat(preview.newGrossSalary()).isEqualByComparingTo("32500");
        assertThat(preview.hikePercent()).isEqualByComparingTo("25.00");
        assertThat(preview.effectiveDate()).isEqualTo(effectiveThisMonth);

        assertThat(salaryRevisionRepository.findByEmployeeIdOrderByEffectiveDateAsc(EMP_A))
                .as("a dry run must not write a revision row")
                .isEmpty();
        assertThat(employeeRepository.findByUserId(EMP_A).orElseThrow().getGrossSalary())
                .as("a dry run must not move the salary")
                .isEqualByComparingTo("26000");
    }

    @Test
    @DisplayName("a dry run still refuses what a real run would refuse")
    void dryRunAppliesTheSameRules() {
        assertThatThrownBy(() -> employeeService.reviseSalaryByUserId(
                EMP_A, request("30000", YearMonth.now().minusMonths(1).atDay(1)), HR, true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("before the current month");
    }

    // ---- applying ----------------------------------------------------------

    @Test
    @DisplayName("a real run moves the salary, re-derives the structure and records the history row")
    void aRealRunAppliesAndRecords() {
        SalaryRevisionPreview applied = employeeService.reviseSalaryByUserId(
                EMP_A, request("32500", effectiveThisMonth), HR, false);

        assertThat(applied.applied()).isTrue();
        assertThat(applied.hikePercent()).isEqualByComparingTo("25.00");

        Employee after = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(after.getGrossSalary()).isEqualByComparingTo("32500");
        assertThat(after.getBasicDA())
                .as("the structure follows gross for a non-overridden employee")
                .isEqualByComparingTo(salaryCalculationService.scaled(new BigDecimal("32500")
                        .multiply(new BigDecimal("0.50"))));

        var history = salaryRevisionRepository.findByEmployeeIdOrderByEffectiveDateAsc(EMP_A);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getPreviousGrossSalary()).isEqualByComparingTo("26000");
        assertThat(history.get(0).getNewGrossSalary()).isEqualByComparingTo("32500");
        assertThat(history.get(0).getRevisedBy()).isEqualTo(HR);
    }

    @Test
    @DisplayName("an unknown employee fails its own row without touching the others")
    void anUnknownEmployeeFailsAlone() {
        assertThatThrownBy(() -> employeeService.reviseSalaryByUserId(
                "NOBODY", request("30000", effectiveThisMonth), HR, false))
                .isInstanceOf(RuntimeException.class);

        employeeService.reviseSalaryByUserId(EMP_B, request("48000", effectiveThisMonth), HR, false);
        assertThat(employeeRepository.findByUserId(EMP_B).orElseThrow().getGrossSalary())
                .isEqualByComparingTo("48000");
    }

    // ---- fixtures ----------------------------------------------------------

    private com.accusharp.hrms.dto.SalaryRevisionRequest request(String gross, LocalDate effective) {
        var request = new com.accusharp.hrms.dto.SalaryRevisionRequest();
        request.setNewGrossSalary(new BigDecimal(gross));
        request.setEffectiveDate(effective);
        request.setReason(SalaryRevisionReason.ANNUAL_INCREMENT);
        request.setRemarks("bulk test");
        return request;
    }

    private List<ParsedCsvRow<BulkSalaryRevisionRow>> parse(String csv) {
        return SalaryRevisionCsvParser.parse(new MockMultipartFile(
                "file", "revisions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
    }

    private void saveEmployee(String userId, Role role, String gross) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("C-" + userId).employeeName(userId)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal(gross)).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false).build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}
