package com.accusharp.hrms;

import com.accusharp.hrms.entity.AttendanceRule;
import com.accusharp.hrms.entity.Company;
import com.accusharp.hrms.entity.SalaryRule;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.repository.AttendanceRuleRepository;
import com.accusharp.hrms.repository.CompanyRepository;
import com.accusharp.hrms.repository.SalaryRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression test for the "one row per company" invariant SalaryRule/
 * AttendanceRule document but previously had no database backstop for - two
 * concurrent first-customization requests for the same company could each
 * insert a row, per the audit finding. This proves the database itself now
 * rejects a second row for a company that already has one, rather than
 * relying solely on the service layer's check-then-act.
 */
@SpringBootTest
class SalaryRuleUniquenessTest {

    @Autowired private CompanyRepository companyRepository;
    @Autowired private SalaryRuleRepository salaryRuleRepository;
    @Autowired private AttendanceRuleRepository attendanceRuleRepository;

    private Company company;

    @BeforeEach
    void setUp() {
        salaryRuleRepository.deleteAll();
        attendanceRuleRepository.deleteAll();
        companyRepository.deleteAll();
        company = companyRepository.save(Company.builder()
                .companyCode("UNIQ-CO").companyName("Uniqueness Co").status(RecordStatus.ACTIVE).build());
    }

    @Test
    @DisplayName("a second SalaryRule row for the same company is rejected at the database level")
    void secondSalaryRuleForSameCompanyIsRejected() {
        salaryRuleRepository.save(ruleForCompany());

        assertThatThrownBy(() -> salaryRuleRepository.saveAndFlush(ruleForCompany()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a second AttendanceRule row for the same company is rejected at the database level")
    void secondAttendanceRuleForSameCompanyIsRejected() {
        attendanceRuleRepository.save(attendanceRuleForCompany());

        assertThatThrownBy(() -> attendanceRuleRepository.saveAndFlush(attendanceRuleForCompany()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private SalaryRule ruleForCompany() {
        SalaryRule rule = SalaryRule.defaultRule();
        rule.setCompany(company);
        return rule;
    }

    private AttendanceRule attendanceRuleForCompany() {
        AttendanceRule rule = AttendanceRule.defaultRule();
        rule.setCompany(company);
        return rule;
    }
}
