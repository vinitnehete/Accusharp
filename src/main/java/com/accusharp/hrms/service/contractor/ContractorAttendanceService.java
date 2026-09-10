package com.accusharp.hrms.service.contractor;

import com.accusharp.hrms.dto.AttendanceGenerationRequest;
import com.accusharp.hrms.dto.AttendanceGenerationResponse;
import com.accusharp.hrms.entity.Contractor;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.service.attendance.AttendanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;

/**
 * Runs attendance generation for exactly one contractor's workforce.
 *
 * <p><b>It owns no attendance logic of its own</b>, and that is the whole
 * design. It resolves the contractor, turns it into a list of {@code userId}s
 * and hands them to the same {@link AttendanceService#generate} the company's
 * own console calls - so a contractor's worker gets the identical punch
 * window, night-shift handover, grace period, policy engine, manual-correction
 * preservation and payroll locking every other person on that site gets.
 * Duplicating any of it here would be the fastest way to have two definitions
 * of a present day.
 *
 * <p>What <em>is</em> separate is the trigger. Generating "everyone" from the
 * company console reaches only the company's own staff (see
 * {@code EmployeeService#getActiveEntities()}), and generating a contractor
 * reaches only that contractor's workers. Neither run can silently sweep the
 * other population in, which is the separation this module was asked for.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractorAttendanceService {

    private final ContractorService contractorService;
    private final ContractorEmployeeService contractorEmployeeService;
    private final AttendanceService attendanceService;

    /**
     * @param includeUnrostered whether a day nobody rostered still becomes a
     *        blank {@code ABSENT} day. Defaults to {@code false} here, the
     *        opposite of the company default, and
     *        {@code AttendanceGenerationRequest#includeUnrostered} names this
     *        exact population as the reason that switch exists: contractor
     *        staff are rostered only on the days they are actually sent in, so
     *        an unrostered day means "not deployed", and marking it absent
     *        would invent a dispute on the contractor's invoice rather than
     *        surface an HR oversight.
     */
    @Transactional
    public AttendanceGenerationResponse generate(Long contractorId, YearMonth month, String generatedBy,
                                                 boolean overwriteManual, boolean dryRun,
                                                 boolean includeUnrostered) {
        Contractor contractor = contractorService.getEntityById(contractorId);
        List<Employee> workers = contractorEmployeeService.activeEntitiesOf(contractorId);
        if (workers.isEmpty()) {
            throw new BusinessRuleException("No active workers are deployed under "
                    + contractor.getContractorName() + " - onboard them before generating attendance");
        }

        AttendanceGenerationRequest request = new AttendanceGenerationRequest();
        request.setMonth(month);
        // Explicit ids, never the "empty means everybody" path: that one resolves
        // to the company's own staff and would generate the wrong population.
        request.setUserIds(workers.stream().map(Employee::getUserId).toList());
        request.setGeneratedBy(generatedBy);
        request.setOverwriteManual(overwriteManual);
        request.setDryRun(dryRun);
        request.setIncludeUnrostered(includeUnrostered);

        AttendanceGenerationResponse response = attendanceService.generate(request);
        log.info("contractor.attendance.generate contractor={} month={} workers={} generated={} dryRun={}",
                contractor.getContractorCode(), month, workers.size(), response.daysGenerated(), dryRun);
        return response;
    }
}
