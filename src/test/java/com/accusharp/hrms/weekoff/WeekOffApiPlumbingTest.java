package com.accusharp.hrms.weekoff;

import com.accusharp.hrms.dto.EmployeeRequest;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.enums.EmployeeStatus;
import com.accusharp.hrms.enums.RecordStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.mapper.EmployeeMapper;
import com.accusharp.hrms.util.EmployeeCsvParser;
import com.accusharp.hrms.util.ParsedCsvRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The week-off field has to reach the API in both directions, or HR can see it
 * and not set it. Pure unit tests - no Spring context.
 *
 * <p>The CSV column matters more than it looks: bulk import is how a client
 * onboards a few hundred employees at once, and a week-off that can only be
 * set one employee at a time through the form is a week-off nobody will set.
 */
class WeekOffApiPlumbingTest {

    private static final String BASE_COLUMNS =
            "userId,employeeCode,employeeName,status,grossSalary,pfBasic,medicalAllowance,otherAllowance";

    private final EmployeeMapper mapper = new EmployeeMapper();

    // ---- outbound ----------------------------------------------------------

    @Test
    @DisplayName("the response carries the configured week-off days")
    void responseCarriesWeekOffDays() {
        Employee employee = employee().weekOffDays(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)).build();

        assertThat(mapper.toResponse(employee).weekOffDays())
                .containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("the response distinguishes unconfigured from explicitly none")
    void responseKeepsUnsetDistinctFromEmpty() {
        // The form has to be able to tell "nobody has set this yet, we are
        // falling back to Sunday" apart from "this employee works every day",
        // or saving the form unchanged would silently change the answer.
        assertThat(mapper.toResponse(employee().build()).weekOffDays()).isNull();
        assertThat(mapper.toResponse(employee().weekOffDays(Set.of()).build()).weekOffDays()).isEmpty();
    }

    // ---- inbound: CSV ------------------------------------------------------

    @Test
    @DisplayName("a bulk import sheet can set several week-off days in one cell")
    void csvParsesSeveralWeekOffDays() {
        String csv = BASE_COLUMNS + ",weekOffDays\n"
                + "EMP200,EMP-200,Two Day Weekend,PERMANENT,30000,12000,1250,500,SATURDAY SUNDAY\n";

        EmployeeRequest request = parseOne(csv);

        assertThat(request.getWeekOffDays())
                .containsExactlyInAnyOrder(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("day names in a bulk import sheet are case and abbreviation tolerant")
    void csvAcceptsHowPeopleActuallyTypeDays() {
        String csv = BASE_COLUMNS + ",weekOffDays\n"
                + "EMP201,EMP-201,Typed By A Human,PERMANENT,30000,12000,1250,500,sun / Tue\n";

        // HR fills these sheets in Excel by hand. Rejecting "sun" would send a
        // three-hundred-row import back over a capitalisation.
        assertThat(parseOne(csv).getWeekOffDays())
                .containsExactlyInAnyOrder(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY);
    }

    @Test
    @DisplayName("a sheet with no week-off column leaves the employee unconfigured")
    void csvWithoutTheColumnLeavesItNull() {
        String csv = BASE_COLUMNS + "\n"
                + "EMP202,EMP-202,Legacy Sheet,PERMANENT,30000,12000,1250,500\n";

        // Every sheet a client already has looks like this. Null means they keep
        // falling back to Sunday, exactly as before.
        assertThat(parseOne(csv).getWeekOffDays()).isNull();
    }

    @Test
    @DisplayName("an explicit NONE in the sheet means no weekly off, not unconfigured")
    void csvNoneMeansWorksEveryDay() {
        String csv = BASE_COLUMNS + ",weekOffDays\n"
                + "EMP203,EMP-203,Works Every Day,PERMANENT,30000,12000,1250,500,NONE\n";

        assertThat(parseOne(csv).getWeekOffDays()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("an unrecognisable day name fails that row rather than the whole import")
    void csvRejectsGarbageDayNames() {
        String csv = BASE_COLUMNS + ",weekOffDays\n"
                + "EMP204,EMP-204,Bad Row,PERMANENT,30000,12000,1250,500,Funday\n";

        List<ParsedCsvRow<EmployeeRequest>> rows = EmployeeCsvParser.parse(multipart(csv));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isOk()).isFalse();
        assertThat(rows.get(0).error()).contains("Funday");
    }

    // ---- helpers -----------------------------------------------------------

    private EmployeeRequest parseOne(String csv) {
        List<ParsedCsvRow<EmployeeRequest>> rows = EmployeeCsvParser.parse(multipart(csv));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).error()).isNull();
        return rows.get(0).value();
    }

    private Employee.EmployeeBuilder employee() {
        return Employee.builder()
                .id(1L).userId("EMP999").employeeCode("EMP-999").employeeName("Mapper Test")
                .status(EmployeeStatus.PERMANENT).recordStatus(RecordStatus.ACTIVE).role(Role.EMPLOYEE)
                .grossSalary(new BigDecimal("30000")).pfBasic(new BigDecimal("15000"))
                .medicalAllowance(BigDecimal.ZERO).otherAllowance(BigDecimal.ZERO);
    }

    private MockMultipartFile multipart(String csv) {
        return new MockMultipartFile("file", "employees.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }
}
