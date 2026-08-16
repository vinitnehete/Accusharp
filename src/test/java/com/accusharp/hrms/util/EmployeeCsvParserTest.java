package com.accusharp.hrms.util;

import com.accusharp.hrms.dto.EmployeeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure parsing tests - no Spring context, no HTTP. The all-or-nothing
 * business rule on the four structure columns lives in {@code
 * EmployeeService#applyStructureOverride}, not here; this only checks the
 * parser wires the columns through (or leaves them null) correctly.
 */
class EmployeeCsvParserTest {

    private static final String BASE_COLUMNS =
            "userId,employeeCode,employeeName,status,grossSalary,pfBasic,medicalAllowance,otherAllowance";

    @Test
    @DisplayName("structure override columns parse when all four are present")
    void parsesStructureOverrideColumns() {
        String csv = BASE_COLUMNS + ",basicDA,hra,conveyanceAllowance,educationAllowance\n"
                + "EMP100,EMP-100,Onboarded Direct,PERMANENT,30000,12000,1250,500,14000,5600,1400,1400\n";

        List<ParsedCsvRow<EmployeeRequest>> rows = EmployeeCsvParser.parse(multipart(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isOk()).isTrue();
        EmployeeRequest request = rows.get(0).value();
        assertThat(request.getBasicDA()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(request.getHra()).isEqualByComparingTo(new BigDecimal("5600"));
        assertThat(request.getConveyanceAllowance()).isEqualByComparingTo(new BigDecimal("1400"));
        assertThat(request.getEducationAllowance()).isEqualByComparingTo(new BigDecimal("1400"));
    }

    @Test
    @DisplayName("structure override columns are null when the sheet leaves them blank")
    void leavesStructureOverrideColumnsNullWhenBlank() {
        String csv = BASE_COLUMNS + "\n"
                + "EMP101,EMP-101,Normal Onboard,PERMANENT,20000,8000,1000,0\n";

        List<ParsedCsvRow<EmployeeRequest>> rows = EmployeeCsvParser.parse(multipart(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isOk()).isTrue();
        EmployeeRequest request = rows.get(0).value();
        assertThat(request.getBasicDA()).isNull();
        assertThat(request.getHra()).isNull();
        assertThat(request.getConveyanceAllowance()).isNull();
        assertThat(request.getEducationAllowance()).isNull();
    }

    @Test
    @DisplayName("numeric columns parse when thousands-separated, e.g. Excel-exported '41,000.00'")
    void parsesThousandsSeparatedNumbers() {
        String csv = BASE_COLUMNS + "\n"
                + "EMP102,EMP-102,Comma Formatted,PERMANENT,\"41,000.00\",\"15,000.00\",\"1,250.00\",\"10,606.00\"\n";

        List<ParsedCsvRow<EmployeeRequest>> rows = EmployeeCsvParser.parse(multipart(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isOk()).isTrue();
        EmployeeRequest request = rows.get(0).value();
        assertThat(request.getGrossSalary()).isEqualByComparingTo(new BigDecimal("41000.00"));
        assertThat(request.getPfBasic()).isEqualByComparingTo(new BigDecimal("15000.00"));
        assertThat(request.getMedicalAllowance()).isEqualByComparingTo(new BigDecimal("1250.00"));
        assertThat(request.getOtherAllowance()).isEqualByComparingTo(new BigDecimal("10606.00"));
    }

    @Test
    @DisplayName("numeric columns parse when currency-symbol- or space-decorated, e.g. '₹ 41,000.00'")
    void parsesCurrencyDecoratedNumbers() {
        String csv = BASE_COLUMNS + "\n"
                + "EMP103,EMP-103,Currency Formatted,PERMANENT,\"₹ 41,000.00\",\"$15,000.00\",1250,0\n";

        List<ParsedCsvRow<EmployeeRequest>> rows = EmployeeCsvParser.parse(multipart(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isOk()).isTrue();
        EmployeeRequest request = rows.get(0).value();
        assertThat(request.getGrossSalary()).isEqualByComparingTo(new BigDecimal("41000.00"));
        assertThat(request.getPfBasic()).isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    private MockMultipartFile multipart(String csv) {
        return new MockMultipartFile("file", "employees.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }
}
