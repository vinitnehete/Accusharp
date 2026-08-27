package com.accusharp.hrms.service.report;

import com.accusharp.hrms.dto.ReportFilter;
import com.accusharp.hrms.entity.Category;
import com.accusharp.hrms.entity.Department;
import com.accusharp.hrms.entity.Designation;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.repository.CategoryRepository;
import com.accusharp.hrms.repository.DepartmentRepository;
import com.accusharp.hrms.repository.DesignationRepository;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves "which employees is this report about, and what are their masters
 * called" once, for every report that needs it.
 *
 * <p>Company scoping is inherited rather than re-implemented: both employee
 * lookups go through {@link EmployeeService}, which is where tenant isolation
 * lives. Like every other {@code REPORT_READ} endpoint these stay
 * company-wide for SUPERVISOR/HR/ADMIN rather than self-service scoped - see
 * {@code PayrollService.getPeriod}'s Javadoc for why reports and raw record
 * lists are treated differently.
 *
 * <p>Master names are batch-loaded by id rather than read off the lazy
 * association per row, the same N+1 fix {@code ReportService.departmentNamesFor}
 * already applies - calling {@code getId()} on a lazy proxy is free, calling
 * {@code getDepartmentName()} is a SELECT.
 */
@Component
@RequiredArgsConstructor
public class ReportScope {

    public static final String UNASSIGNED = "Unassigned";

    private final EmployeeService employeeService;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final CategoryRepository categoryRepository;

    /**
     * Every employee of the caller's company matching the filter, including
     * deactivated ones - a month already paid must stay auditable after the
     * employee leaves.
     */
    public List<Employee> employees(ReportFilter filter) {
        return filtered(employeeService.getAllEntities(), filter);
    }

    /** Active employees only - for reports about the present rather than a closed period. */
    public List<Employee> activeEmployees(ReportFilter filter) {
        return filtered(employeeService.getActiveEntities(), filter);
    }

    public Map<String, Employee> byUserId(Collection<Employee> employees) {
        Map<String, Employee> byUserId = new LinkedHashMap<>();
        employees.forEach(employee -> byUserId.put(employee.getUserId(), employee));
        return byUserId;
    }

    /** Department, designation and category names for this batch, in three queries total. */
    public MasterNames names(Collection<Employee> employees) {
        return new MasterNames(
                lookup(ids(employees, Employee::getDepartment, Department::getId),
                        departmentRepository::findAllById, Department::getId, Department::getDepartmentName),
                lookup(ids(employees, Employee::getDesignation, Designation::getId),
                        designationRepository::findAllById, Designation::getId, Designation::getDesignationName),
                lookup(ids(employees, Employee::getCategory, Category::getId),
                        categoryRepository::findAllById, Category::getId, Category::getCategoryName));
    }

    private List<Employee> filtered(List<Employee> employees, ReportFilter filter) {
        ReportFilter effective = filter == null ? ReportFilter.NONE : filter;
        return employees.stream()
                .filter(employee -> matches(employee, effective))
                .sorted(Comparator.comparing(Employee::getUserId))
                .toList();
    }

    private boolean matches(Employee employee, ReportFilter filter) {
        return idMatches(filter.departmentId(), employee.getDepartment() == null ? null
                        : employee.getDepartment().getId())
                && idMatches(filter.designationId(), employee.getDesignation() == null ? null
                        : employee.getDesignation().getId())
                && idMatches(filter.categoryId(), employee.getCategory() == null ? null
                        : employee.getCategory().getId());
    }

    private boolean idMatches(Long wanted, Long actual) {
        return wanted == null || wanted.equals(actual);
    }

    private <T> List<Long> ids(Collection<Employee> employees, Function<Employee, T> association,
                               Function<T, Long> id) {
        return employees.stream()
                .map(association)
                .filter(Objects::nonNull)
                .map(id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private <T> Map<Long, String> lookup(List<Long> ids, Function<List<Long>, Iterable<T>> finder,
                                         Function<T, Long> id, Function<T, String> name) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        finder.apply(ids).forEach(entity -> names.put(id.apply(entity), name.apply(entity)));
        return names;
    }

    /** The three master name maps, with null-safe lookups off an employee. */
    public record MasterNames(Map<Long, String> departments, Map<Long, String> designations,
                              Map<Long, String> categories) {

        public String department(Employee employee) {
            return resolve(departments, employee == null || employee.getDepartment() == null ? null
                    : employee.getDepartment().getId());
        }

        public String designation(Employee employee) {
            return resolve(designations, employee == null || employee.getDesignation() == null ? null
                    : employee.getDesignation().getId());
        }

        public String category(Employee employee) {
            return resolve(categories, employee == null || employee.getCategory() == null ? null
                    : employee.getCategory().getId());
        }

        private String resolve(Map<Long, String> names, Long id) {
            return id == null ? UNASSIGNED : names.getOrDefault(id, UNASSIGNED);
        }
    }
}
