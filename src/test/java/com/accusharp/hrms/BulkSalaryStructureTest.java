package com.accusharp.hrms;

import com.accusharp.hrms.dto.BulkSalaryStructureRow;
import com.accusharp.hrms.dto.SalaryRevisionRequest;
import com.accusharp.hrms.dto.SalaryStructurePreview;
import com.accusharp.hrms.dto.SalaryStructureRequest;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.SalaryStructureChangeType;
import com.accusharp.hrms.entity.SalaryStructureRevision;
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
import com.accusharp.hrms.util.SalaryStructureCsvParser;
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
 * Bulk salary-structure override - the consequential half of bulk salary work.
 *
 * <p>An override <em>freezes</em> the four components: they stop following
 * gross salary, so every later revision has to restate all four or be refused.
 * That is a rule change for the employee, not just a value change, which is why
 * this is a separate upload from a bulk revision and why the preview reports
 * who was not already overridden.
 */
@SpringBootTest
class BulkSalaryStructureTest {

    private static final String HR = "HR810";
    private static final String EMP_A = "EMP811";
    private static final String EMP_B = "EMP812";

    @Autowired private EmployeeService employeeService;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private SalaryRevisionRepository salaryRevisionRepository;
    @Autowired private SalaryStructureRevisionRepository salaryStructureRevisionRepository;
    @Autowired private SalaryRuleService salaryRuleService;
    @Autowired private SalaryCalculationService salaryCalculationService;

    @BeforeEach
    void setUp() {
        salaryRevisionRepository.deleteAll();
        salaryStructureRevisionRepository.deleteAll();
        employeeRepository.deleteAll();
        saveEmployee(HR, Role.HR);
        saveEmployee(EMP_A, Role.EMPLOYEE);
        saveEmployee(EMP_B, Role.EMPLOYEE);
    }

    // ---- parsing -----------------------------------------------------------

    @Test
    @DisplayName("a well-formed file parses one override per row")
    void parsesEveryRow() {
        List<ParsedCsvRow<BulkSalaryStructureRow>> rows = parse("""
                userId,basicDA,hra,conveyanceAllowance,educationAllowance
                EMP811,14000,5600,1400,1400
                EMP812,"16,000",6400,1600,1600
                """);

        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(ParsedCsvRow::isOk);
        assertThat(rows.get(0).value().userId()).isEqualTo(EMP_A);
        assertThat(rows.get(0).value().request().getBasicDA()).isEqualByComparingTo("14000");
        assertThat(rows.get(1).value().request().getBasicDA()).isEqualByComparingTo("16000");
    }

    @Test
    @DisplayName("all four components are required - a partial override is refused")
    void aPartialOverrideIsRefused() {
        List<ParsedCsvRow<BulkSalaryStructureRow>> rows = parse("""
                userId,basicDA,hra,conveyanceAllowance,educationAllowance
                EMP811,14000,5600,1400,
                """);

        assertThat(rows.get(0).isOk()).isFalse();
        assertThat(rows.get(0).error()).contains("educationAllowance").contains("required");
    }

    // ---- applying ----------------------------------------------------------

    @Test
    @DisplayName("an override freezes the structure and stops it following gross salary")
    void anOverrideFreezesTheStructure() {
        SalaryStructurePreview preview = employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, false);

        assertThat(preview.applied()).isTrue();
        assertThat(preview.alreadyOverridden())
                .as("this file is what freezes them")
                .isFalse();

        Employee after = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(after.isSalaryStructureOverridden()).isTrue();
        assertThat(after.getBasicDA()).isEqualByComparingTo("14000");
        assertThat(after.getHra()).isEqualByComparingTo("5600");
    }

    @Test
    @DisplayName("the preview reports what the components add up to against gross salary")
    void thePreviewReportsTheGapFromGross() {
        // 14000 + 5600 + 1400 + 1400 + medical 1250 + other 0 = 23650, against a
        // gross of 26000 - a 2350 shortfall the uploader should see before committing.
        SalaryStructurePreview preview = employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, true);

        assertThat(preview.grossSalary()).isEqualByComparingTo("26000");
        assertThat(preview.totalWage()).isEqualByComparingTo("23650");
        assertThat(preview.differenceFromGross()).isEqualByComparingTo("-2350");
    }

    @Test
    @DisplayName("a dry run reports the override and writes nothing")
    void dryRunDoesNotFreezeAnyone() {
        BigDecimal basicBefore = employeeRepository.findByUserId(EMP_A).orElseThrow().getBasicDA();

        SalaryStructurePreview preview = employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, true);

        assertThat(preview.applied()).isFalse();
        Employee after = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(after.isSalaryStructureOverridden())
                .as("a dry run must not freeze the structure")
                .isFalse();
        assertThat(after.getBasicDA()).isEqualByComparingTo(basicBefore);
    }

    @Test
    @DisplayName("an already-overridden employee is reported as such")
    void anAlreadyOverriddenEmployeeIsFlagged() {
        employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, false);

        SalaryStructurePreview second = employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("15000", "6000", "1500", "1500"), HR, true);

        assertThat(second.alreadyOverridden()).isTrue();
    }

    // ---- the full salary picture -------------------------------------------

    @Test
    @DisplayName("medical, other and gross are written to the employee when the row carries them")
    void theWholePictureIsPersisted() {
        SalaryStructureRequest full = structure("15000", "6000", "1500", "1500");
        full.setMedicalAllowance(new BigDecimal("2000"));
        full.setOtherAllowance(new BigDecimal("1000"));
        full.setGrossSalary(new BigDecimal("30000"));

        SalaryStructurePreview preview = employeeService.overrideSalaryStructureByUserId(EMP_A, full, HR, false);

        Employee after = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(after.getBasicDA()).isEqualByComparingTo("15000");
        assertThat(after.getHra()).isEqualByComparingTo("6000");
        assertThat(after.getConveyanceAllowance()).isEqualByComparingTo("1500");
        assertThat(after.getEducationAllowance()).isEqualByComparingTo("1500");
        assertThat(after.getMedicalAllowance()).isEqualByComparingTo("2000");
        assertThat(after.getOtherAllowance()).isEqualByComparingTo("1000");
        assertThat(after.getGrossSalary()).isEqualByComparingTo("30000");
        // grossSalaryWage is the sum of all six and is recomputed on write.
        assertThat(after.getGrossSalaryWage()).isEqualByComparingTo("27000");

        assertThat(preview.previousGrossSalary()).isEqualByComparingTo("26000");
        assertThat(preview.grossSalary()).isEqualByComparingTo("30000");
        assertThat(preview.differenceFromGross()).isEqualByComparingTo("-3000");
    }

    @Test
    @DisplayName("a row that omits medical, other or gross leaves those figures alone")
    void omittedColumnsAreLeftAsTheyAre() {
        employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, false);

        Employee after = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(after.getMedicalAllowance()).isEqualByComparingTo("1250");
        assertThat(after.getOtherAllowance()).isEqualByComparingTo("0");
        assertThat(after.getGrossSalary())
                .as("gross is untouched when the row does not carry it")
                .isEqualByComparingTo("26000");
    }

    @Test
    @DisplayName("the report's own column headings are accepted, not just the field names")
    void reportHeadingsAreAccepted() {
        List<ParsedCsvRow<BulkSalaryStructureRow>> rows = parse("""
                userId,Basic + DA,HRA,Con. Allow,Edu. Allow,Med. Allow,Other Allow,Gross Salary
                EMP811,15000,6000,1500,1500,2000,1000,30000
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isOk()).as(rows.get(0).error()).isTrue();
        SalaryStructureRequest parsed = rows.get(0).value().request();
        assertThat(parsed.getBasicDA()).isEqualByComparingTo("15000");
        assertThat(parsed.getConveyanceAllowance()).isEqualByComparingTo("1500");
        assertThat(parsed.getEducationAllowance()).isEqualByComparingTo("1500");
        assertThat(parsed.getMedicalAllowance()).isEqualByComparingTo("2000");
        assertThat(parsed.getOtherAllowance()).isEqualByComparingTo("1000");
        assertThat(parsed.getGrossSalary()).isEqualByComparingTo("30000");
    }

    // ---- the consequence ---------------------------------------------------

    @Test
    @DisplayName("once overridden, a later salary revision must restate all four components")
    void anOverriddenEmployeeMustRestateComponentsOnEveryRevision() {
        employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, false);

        SalaryRevisionRequest bare = new SalaryRevisionRequest();
        bare.setNewGrossSalary(new BigDecimal("32000"));
        bare.setEffectiveDate(YearMonth.now().atDay(1));
        bare.setReason(SalaryRevisionReason.ANNUAL_INCREMENT);

        assertThatThrownBy(() -> employeeService.reviseSalaryByUserId(EMP_A, bare, HR, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("structure is overridden");

        // A non-overridden colleague takes the same bare revision without complaint.
        SalaryRevisionRequest same = new SalaryRevisionRequest();
        same.setNewGrossSalary(new BigDecimal("32000"));
        same.setEffectiveDate(YearMonth.now().atDay(1));
        same.setReason(SalaryRevisionReason.ANNUAL_INCREMENT);
        employeeService.reviseSalaryByUserId(EMP_B, same, HR, false);
        assertThat(employeeRepository.findByUserId(EMP_B).orElseThrow().getGrossSalary())
                .isEqualByComparingTo("32000");
    }

    @Test
    @DisplayName("regenerating puts an overridden employee back on the company rule")
    void regenerateIsTheWayBack() {
        employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, false);
        Employee overridden = employeeRepository.findByUserId(EMP_A).orElseThrow();

        employeeService.regenerateSalaryStructure(overridden.getId(), HR);

        Employee after = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(after.isSalaryStructureOverridden()).isFalse();
        assertThat(after.getBasicDA())
                .as("back to 50% of gross under the seeded rule")
                .isEqualByComparingTo(salaryCalculationService.scaled(new BigDecimal("13000")));
    }

    @Test
    @DisplayName("an unknown employee fails its own row")
    void anUnknownEmployeeFails() {
        assertThatThrownBy(() -> employeeService.overrideSalaryStructureByUserId(
                "NOBODY", structure("1", "1", "1", "1"), HR, false))
                .isInstanceOf(RuntimeException.class);
    }

    // ---- history -----------------------------------------------------------

    @Test
    @DisplayName("an override records what the components were before it")
    void anOverrideRecordsItsBeforeImage() {
        Employee before = employeeRepository.findByUserId(EMP_A).orElseThrow();
        BigDecimal basicBefore = before.getBasicDA();
        BigDecimal hraBefore = before.getHra();

        SalaryStructureRequest full = structure("15000", "6000", "1500", "1500");
        full.setMedicalAllowance(new BigDecimal("2000"));
        full.setGrossSalary(new BigDecimal("30000"));
        employeeService.overrideSalaryStructureByUserId(EMP_A, full, HR, false);

        Employee saved = employeeRepository.findByUserId(EMP_A).orElseThrow();
        List<SalaryStructureRevision> history =
                employeeService.getSalaryStructureRevisions(saved.getId());

        assertThat(history).hasSize(1);
        SalaryStructureRevision row = history.get(0);
        assertThat(row.getChangeType()).isEqualTo(SalaryStructureChangeType.OVERRIDE);
        assertThat(row.getRevisedBy()).isEqualTo(HR);

        assertThat(row.getPreviousBasicDA()).isEqualByComparingTo(basicBefore);
        assertThat(row.getPreviousHra()).isEqualByComparingTo(hraBefore);
        assertThat(row.getPreviousMedicalAllowance()).isEqualByComparingTo("1250");
        assertThat(row.getPreviousGrossSalary()).isEqualByComparingTo("26000");
        assertThat(row.isPreviouslyOverridden()).isFalse();

        assertThat(row.getNewBasicDA()).isEqualByComparingTo("15000");
        assertThat(row.getNewMedicalAllowance()).isEqualByComparingTo("2000");
        assertThat(row.getNewGrossSalary()).isEqualByComparingTo("30000");
        assertThat(row.isNowOverridden()).isTrue();
    }

    @Test
    @DisplayName("going back to the company rule is recorded too")
    void regeneratingRecordsItsBeforeImage() {
        employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, false);
        Employee overridden = employeeRepository.findByUserId(EMP_A).orElseThrow();

        employeeService.regenerateSalaryStructure(overridden.getId(), HR);

        List<SalaryStructureRevision> history =
                employeeService.getSalaryStructureRevisions(overridden.getId());
        assertThat(history).hasSize(2);

        SalaryStructureRevision newest = history.get(0);
        assertThat(newest.getChangeType()).isEqualTo(SalaryStructureChangeType.REGENERATE);
        assertThat(newest.isPreviouslyOverridden()).isTrue();
        assertThat(newest.isNowOverridden()).isFalse();
        assertThat(newest.getPreviousBasicDA())
                .as("the hand-set value it discarded is still readable")
                .isEqualByComparingTo("14000");
        assertThat(newest.getNewBasicDA())
                .as("back to 50% of gross under the seeded rule")
                .isEqualByComparingTo(salaryCalculationService.scaled(new BigDecimal("13000")));
    }

    @Test
    @DisplayName("a dry run writes no history")
    void dryRunWritesNoHistory() {
        employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, true);

        Employee employee = employeeRepository.findByUserId(EMP_A).orElseThrow();
        assertThat(employeeService.getSalaryStructureRevisions(employee.getId())).isEmpty();
    }

    @Test
    @DisplayName("successive overrides each keep their own before-image")
    void historyIsAppendOnly() {
        employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("14000", "5600", "1400", "1400"), HR, false);
        employeeService.overrideSalaryStructureByUserId(
                EMP_A, structure("15000", "6000", "1500", "1500"), HR, false);

        Employee employee = employeeRepository.findByUserId(EMP_A).orElseThrow();
        List<SalaryStructureRevision> history =
                employeeService.getSalaryStructureRevisions(employee.getId());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getPreviousBasicDA())
                .as("the newest row's before-image is the previous override")
                .isEqualByComparingTo("14000");
        assertThat(history.get(0).getNewBasicDA()).isEqualByComparingTo("15000");
    }

    // ---- fixtures ----------------------------------------------------------

    private SalaryStructureRequest structure(String basic, String hra, String conveyance, String education) {
        SalaryStructureRequest request = new SalaryStructureRequest();
        request.setBasicDA(new BigDecimal(basic));
        request.setHra(new BigDecimal(hra));
        request.setConveyanceAllowance(new BigDecimal(conveyance));
        request.setEducationAllowance(new BigDecimal(education));
        return request;
    }

    private List<ParsedCsvRow<BulkSalaryStructureRow>> parse(String csv) {
        return SalaryStructureCsvParser.parse(new MockMultipartFile(
                "file", "structures.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
    }

    private void saveEmployee(String userId, Role role) {
        Employee employee = Employee.builder()
                .userId(userId).employeeCode("C-" + userId).employeeName(userId)
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(role)
                .joiningDate(LocalDate.of(2022, 1, 1))
                .grossSalary(new BigDecimal("26000")).pfBasic(new BigDecimal("9000"))
                .medicalAllowance(new BigDecimal("1250")).otherAllowance(BigDecimal.ZERO)
                .overtimeEligible(false).build();
        salaryCalculationService.applyCalculatedFields(employee, salaryRuleService.getActiveRule());
        employeeRepository.save(employee);
    }
}
