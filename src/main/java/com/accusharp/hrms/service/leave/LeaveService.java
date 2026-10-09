package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveHrDirectRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.LeaveResponse;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveOrigin;
import com.accusharp.hrms.enums.LeaveApprovalFlow;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.enums.PermissionCode;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.security.AuthorizationService;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.EmployeeService;
import com.accusharp.hrms.service.policy.WorkPolicyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

/**
 * Leave workflow: employee applies, the supervisor endorses, HR approves.
 *
 * <p>Balance moves at exactly two points - it is consumed on final approval and
 * restored on cancellation. A rejection never touches it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeaveService {

    private static final List<LeaveStatus> BLOCKING_STATUSES =
            List.of(LeaveStatus.PENDING, LeaveStatus.SUPERVISOR_APPROVED, LeaveStatus.APPROVED);

    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveCalculationService leaveCalculationService;
    private final EmployeeService employeeService;
    private final AuthorizationService authorizationService;
    private final WorkPolicyResolver workPolicyResolver;
    private final AuditService auditService;

    @Transactional
    public LeaveResponse apply(LeaveRequestPayload payload) {
        Employee employee = employeeService.getEntityByUserId(payload.getUserId());
        // From here on the person's stored id, never the typed one: the request, the
        // balance and every guard that later compares ids must all say "CVHR", not "cvhr".
        String userId = employee.getUserId();
        employeeService.assertNotCompanyAccount(employee);
        // Also blocks a plain EMPLOYEE filing leave as an unrelated coworker -
        // a SUPERVISOR may still file on behalf of their own directly-supervised
        // team, same relation this app already uses for approvals and scheduling.
        employeeService.assertSelfOrManages(userId);

        if (payload.getFromDate().isAfter(payload.getToDate())) {
            throw new BusinessRuleException("fromDate must be on or before toDate");
        }
        if (payload.getDuration() != LeaveDuration.FULL_DAY
                && !payload.getFromDate().equals(payload.getToDate())) {
            throw new BusinessRuleException("A half day leave must start and end on the same date");
        }
        assertWithinOneLeaveYear(employee, payload.getFromDate(), payload.getToDate());
        assertNoOverlap(userId, payload.getFromDate(), payload.getToDate());

        BigDecimal totalDays = leaveCalculationService.countDays(
                payload.getFromDate(), payload.getToDate(), payload.getDuration());

        // Fail early rather than letting the approver hit an empty balance.
        assertBalanceAvailable(userId, payload.getLeaveType(),
                leaveYearOf(employee, payload.getFromDate()), totalDays);

        // Who has to agree, for this employee's population - the two-step flow
        // unless a work policy says otherwise.
        LeaveApprovalFlow flow = workPolicyResolver.leaveApprovalFlow(employee, payload.getFromDate());
        boolean autoApproved = flow == LeaveApprovalFlow.AUTO_APPROVE;

        LeaveRequest request = LeaveRequest.builder()
                .userId(userId)
                .leaveType(payload.getLeaveType())
                .fromDate(payload.getFromDate())
                .toDate(payload.getToDate())
                .duration(payload.getDuration())
                .totalDays(totalDays)
                .reason(payload.getReason())
                .status(autoApproved ? LeaveStatus.APPROVED : LeaveStatus.PENDING)
                .origin(LeaveOrigin.SELF_SERVICE)
                .supervisorId(employee.getSupervisor() == null ? null : employee.getSupervisor().getUserId())
                .appliedAt(Instant.now())
                .build();

        if (autoApproved) {
            // Balance moves here for the same reason it moves on approval: this
            // *is* the approval. Every check above has already run, so automatic
            // approval is never a way around an empty balance or an overlap.
            leaveBalanceService.consume(userId,
                    leaveYearOf(employee, payload.getFromDate()), payload.getLeaveType(), totalDays);
            request.setDecidedAt(Instant.now());
            request.setApprovalComments("Approved automatically - this population's leave needs no approval");
        }

        log.info("leave.apply userId={} type={} from={} to={} days={} flow={}", userId,
                payload.getLeaveType(), payload.getFromDate(), payload.getToDate(), totalDays, flow);
        LeaveResponse response = toResponse(leaveRequestRepository.save(request));
        if (autoApproved) {
            auditService.record("LEAVE_AUTO_APPROVE", "LeaveRequest", String.valueOf(response.id()),
                    AuditOutcome.SUCCESS, "userId=" + userId + " days=" + totalDays);
        }
        return response;
    }

    /**
     * HR/ADMIN enters an already-approved leave directly, skipping apply and
     * endorsement entirely - for backfilling a day that already happened
     * (an employee took an informal day off and HR wants attendance/payroll
     * to reflect it correctly), not a forward-looking request.
     *
     * <p>Runs the exact same date/overlap/balance validations {@link #apply}
     * does - a manual entry hard-blocks on insufficient balance the same way
     * a self-service one does, rather than being allowed to silently
     * overdraw it - then goes straight to {@code APPROVED} and consumes
     * balance immediately, same as {@link #approve} does. {@link
     * LeaveRequest#getOrigin()} is what distinguishes this from a normally
     * self-service-approved leave once both sit at {@code APPROVED}.
     */
    @Transactional
    public LeaveResponse hrDirectCreate(LeaveHrDirectRequest request) {
        Employee employee = employeeService.getEntityByUserId(request.getUserId()); // tenant check, same as assertTargetAccessible
        String userId = employee.getUserId(); // the stored id, whatever case was typed - see apply
        assertActorCan(request.getApproverId(), PermissionCode.LEAVE_APPROVE, "Entering an approved leave");
        employeeService.assertManages(userId);

        if (request.getFromDate().isAfter(request.getToDate())) {
            throw new BusinessRuleException("fromDate must be on or before toDate");
        }
        if (request.getDuration() != LeaveDuration.FULL_DAY
                && !request.getFromDate().equals(request.getToDate())) {
            throw new BusinessRuleException("A half day leave must start and end on the same date");
        }
        assertWithinOneLeaveYear(employee, request.getFromDate(), request.getToDate());
        assertNoOverlap(userId, request.getFromDate(), request.getToDate());

        BigDecimal totalDays = leaveCalculationService.countDays(
                request.getFromDate(), request.getToDate(), request.getDuration());
        assertBalanceAvailable(userId, request.getLeaveType(),
                leaveYearOf(employee, request.getFromDate()), totalDays);

        leaveBalanceService.consume(userId, leaveYearOf(employee, request.getFromDate()),
                request.getLeaveType(), totalDays);

        LeaveRequest entity = LeaveRequest.builder()
                .userId(userId)
                .leaveType(request.getLeaveType())
                .fromDate(request.getFromDate())
                .toDate(request.getToDate())
                .duration(request.getDuration())
                .totalDays(totalDays)
                .reason(request.getReason())
                .status(LeaveStatus.APPROVED)
                .origin(LeaveOrigin.HR_DIRECT)
                .approverId(request.getApproverId())
                .approvalComments(request.getComments())
                .appliedAt(Instant.now())
                .decidedAt(Instant.now())
                .build();

        log.info("leave.hrDirectCreate userId={} type={} from={} to={} days={} by={}",
                userId, request.getLeaveType(), request.getFromDate(), request.getToDate(),
                totalDays, request.getApproverId());
        LeaveRequest saved = leaveRequestRepository.save(entity);
        auditService.record("LEAVE_HR_DIRECT_CREATE", "LeaveRequest", String.valueOf(saved.getId()),
                AuditOutcome.SUCCESS, "userId=" + userId + " days=" + totalDays);
        return toResponse(saved);
    }

    /** Step one: the employee's own supervisor endorses the request. */
    @Transactional
    public LeaveResponse supervisorApprove(Long id, LeaveDecisionRequest decision) {
        LeaveRequest request = getEntity(id);
        assertTargetAccessible(request);
        assertStatus(request, LeaveStatus.PENDING);
        assertEndorsementApplies(request);
        assertSupervisorOf(decision.getApproverId(), request.getUserId());

        request.setStatus(LeaveStatus.SUPERVISOR_APPROVED);
        request.setApproverId(decision.getApproverId());
        request.setApprovalComments(decision.getComments());
        LeaveResponse response = toResponse(leaveRequestRepository.save(request));
        auditService.record("LEAVE_SUPERVISOR_APPROVE", "LeaveRequest", String.valueOf(id),
                AuditOutcome.SUCCESS, "userId=" + request.getUserId());
        return response;
    }

    /** Step two: HR gives final approval, which is what consumes balance. */
    @Transactional
    public LeaveResponse approve(Long id, LeaveDecisionRequest decision) {
        LeaveRequest request = getEntity(id);
        assertTargetAccessible(request);
        if (!request.getStatus().isOpen()) {
            throw new BusinessRuleException("Leave is already " + request.getStatus());
        }
        assertActorCan(decision.getApproverId(), PermissionCode.LEAVE_APPROVE, "Final leave approval");
        employeeService.assertManages(request.getUserId());

        leaveBalanceService.consume(request.getUserId(), leaveYearOf(request.getUserId(), request.getFromDate()),
                request.getLeaveType(), request.getTotalDays());

        request.setStatus(LeaveStatus.APPROVED);
        request.setApproverId(decision.getApproverId());
        request.setApprovalComments(decision.getComments());
        request.setDecidedAt(Instant.now());

        log.info("leave.approve id={} userId={} days={}", id, request.getUserId(), request.getTotalDays());
        LeaveResponse response = toResponse(leaveRequestRepository.save(request));
        auditService.record("LEAVE_APPROVE", "LeaveRequest", String.valueOf(id), AuditOutcome.SUCCESS,
                "userId=" + request.getUserId() + " days=" + request.getTotalDays());
        return response;
    }

    /** Rejection is a dead end - balance is untouched. */
    @Transactional
    public LeaveResponse reject(Long id, LeaveDecisionRequest decision) {
        LeaveRequest request = getEntity(id);
        assertTargetAccessible(request);
        employeeService.assertManages(request.getUserId());
        if (!request.getStatus().isOpen()) {
            throw new BusinessRuleException("Leave is already " + request.getStatus());
        }
        request.setStatus(LeaveStatus.REJECTED);
        request.setApproverId(decision.getApproverId());
        request.setApprovalComments(decision.getComments());
        request.setDecidedAt(Instant.now());
        LeaveResponse response = toResponse(leaveRequestRepository.save(request));
        auditService.record("LEAVE_REJECT", "LeaveRequest", String.valueOf(id), AuditOutcome.SUCCESS,
                "userId=" + request.getUserId());
        return response;
    }

    /** Cancelling an approved leave gives the days back. */
    @Transactional
    public LeaveResponse cancel(Long id, LeaveDecisionRequest decision) {
        LeaveRequest request = getEntity(id);
        assertTargetAccessible(request);
        employeeService.assertManages(request.getUserId());
        if (request.getStatus() == LeaveStatus.CANCELLED || request.getStatus() == LeaveStatus.REJECTED) {
            throw new BusinessRuleException("Leave is already " + request.getStatus());
        }
        if (request.getStatus().consumesBalance()) {
            leaveBalanceService.restore(request.getUserId(), leaveYearOf(request.getUserId(), request.getFromDate()),
                    request.getLeaveType(), request.getTotalDays());
        }
        request.setStatus(LeaveStatus.CANCELLED);
        request.setApproverId(decision.getApproverId());
        request.setApprovalComments(decision.getComments());
        request.setDecidedAt(Instant.now());
        LeaveResponse response = toResponse(leaveRequestRepository.save(request));
        auditService.record("LEAVE_CANCEL", "LeaveRequest", String.valueOf(id), AuditOutcome.SUCCESS,
                "userId=" + request.getUserId());
        return response;
    }

    @Transactional(readOnly = true)
    public LeaveResponse getById(Long id) {
        return toResponse(getEntity(id));
    }

    @Transactional(readOnly = true)
    public List<LeaveResponse> getHistory(String userId) {
        employeeService.getEntityByUserId(userId);
        return leaveRequestRepository.findAllByUserIdOrderByFromDateDesc(userId).stream()
                .map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveResponse> getPendingFor(String supervisorUserId) {
        return leaveRequestRepository.findAllBySupervisorIdAndStatus(supervisorUserId, LeaveStatus.PENDING)
                .stream().flatMap(this::toResponseIfAccessible).toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveResponse> getByStatus(LeaveStatus status) {
        return leaveRequestRepository.findAllByStatus(status).stream()
                .flatMap(this::toResponseIfAccessible).toList();
    }

    /** Every approved leave overlapping a window - the leave calendar. */
    @Transactional(readOnly = true)
    public List<LeaveResponse> getCalendar(LocalDate fromDate, LocalDate toDate) {
        return leaveRequestRepository
                .findAllByStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                        List.of(LeaveStatus.APPROVED), toDate, fromDate)
                .stream().flatMap(this::toResponseIfAccessible).toList();
    }

    // ---- helpers -----------------------------------------------------------

    private LeaveRequest getEntity(Long id) {
        return leaveRequestRepository.findById(id).orElseThrow(() -> NotFoundException.of("Leave request", id));
    }

    /**
     * Called first, before any status/balance mutation, in every decision
     * method ({@code supervisorApprove}/{@code approve}/{@code reject}/
     * {@code cancel}) - {@code getEntity} is a raw {@code findById} with no
     * company filter, and {@code assertHrOrAdmin}/the ADMIN-or-HR branch of
     * {@code assertSupervisorOf} only check the *approver's* company, never
     * the leave's own target employee. Without this call up front, a
     * cross-company decision was only ever caught incidentally, by {@code
     * toResponse}'s tenant check at the very end rolling back the whole
     * transaction - correct by accident, not by design.
     */
    private void assertTargetAccessible(LeaveRequest request) {
        employeeService.getEntityByUserId(request.getUserId());
    }

    private void assertStatus(LeaveRequest request, LeaveStatus expected) {
        if (request.getStatus() != expected) {
            throw new BusinessRuleException("Leave is " + request.getStatus() + ", expected " + expected);
        }
    }

    private void assertNoOverlap(String userId, LocalDate fromDate, LocalDate toDate) {
        boolean overlaps = !leaveRequestRepository
                .findAllByUserIdAndStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                        userId, BLOCKING_STATUSES, toDate, fromDate)
                .isEmpty();
        if (overlaps) {
            throw new BusinessRuleException("An open or approved leave already covers part of this range");
        }
    }

    /**
     * The leave year a date's leave comes out of - the company's own year,
     * starting in January or April (see {@link LeaveYears}). Approval, cancellation
     * and the balance check all go through here, so a leave always comes out of
     * and goes back to the same balance.
     */
    private int leaveYearOf(Employee employee, LocalDate date) {
        return LeaveYears.leaveYearOf(date, LeaveYears.startMonthOf(employee));
    }

    private int leaveYearOf(String userId, LocalDate date) {
        return leaveYearOf(employeeService.getEntityByUserId(userId), date);
    }

    /**
     * One request, one balance: a leave running across the end of the company's
     * leave year - 31 December, or 31 March for a financial-year company - would
     * have to come out of two, so it has to be split instead.
     */
    private void assertWithinOneLeaveYear(Employee employee, LocalDate fromDate, LocalDate toDate) {
        int startMonth = LeaveYears.startMonthOf(employee);
        int fromYear = LeaveYears.leaveYearOf(fromDate, startMonth);
        if (fromYear != LeaveYears.leaveYearOf(toDate, startMonth)) {
            throw new BusinessRuleException("A leave request cannot span two leave years - split it at "
                    + LeaveYears.endOf(fromYear, startMonth));
        }
    }

    private void assertBalanceAvailable(String userId, LeaveType leaveType, int year, BigDecimal totalDays) {
        if (!leaveType.isPaid()) {
            return;
        }
        BigDecimal available = leaveBalanceService.getOrCreate(userId, year, leaveType).available();
        if (available.compareTo(totalDays) < 0) {
            throw new BusinessRuleException("Insufficient " + leaveType + " balance: available "
                    + available + ", requested " + totalDays);
        }
    }

    /**
     * An HR-only population has no endorsement step - typically because there is
     * nobody above them to endorse, so a request waiting for one would wait
     * forever. Refused here rather than silently accepted, so the flow a company
     * configured is the flow it gets.
     */
    private void assertEndorsementApplies(LeaveRequest request) {
        Employee employee = employeeService.getEntityByUserId(request.getUserId());
        if (workPolicyResolver.leaveApprovalFlow(employee, request.getFromDate())
                == LeaveApprovalFlow.HR_ONLY) {
            throw new BusinessRuleException("This employee's leave is decided by HR directly - "
                    + "there is no endorsement step to complete");
        }
    }

    /**
     * Endorsement is for whoever manages the employee: their supervisor, a
     * director above that supervisor ({@code DataScope.ALL_REPORTS}), or HR and
     * ADMIN, who reach the whole company.
     */
    private void assertSupervisorOf(String approverId, String userId) {
        Employee approver = employeeService.getEntityByUserId(approverId);
        // Stored forms on both sides: "cvhr" is HR's own record, however it was typed
        // - or stored, by a request written before the id was normalised.
        if (approver.getUserId().equals(employeeService.storedUserId(userId))
                || !employeeService.managesEmployee(approver, userId)) {
            throw new BusinessRuleException("Approver " + approverId + " does not manage " + userId);
        }
    }

    /**
     * The approver must hold the permission - through their role or a custom
     * role - rather than be HR or ADMIN by name, so a custom role granting
     * {@code LEAVE_APPROVE} works past the controller gate. Whose leave they may
     * decide is checked separately: {@code EmployeeService#assertManages}.
     */
    private void assertActorCan(String approverId, PermissionCode permission, String action) {
        Employee approver = employeeService.getEntityByUserId(approverId);
        if (!authorizationService.employeeCan(approver, permission.name())) {
            throw new BusinessRuleException(action + " requires the " + permission + " permission");
        }
    }

    private LeaveResponse toResponse(LeaveRequest request) {
        Employee employee = employeeService.getEntityByUserId(request.getUserId());
        // Self-service scoping: reads (getById/getHistory/getPendingFor/getByStatus/
        // getCalendar) all funnel through here, so one check covers all of them. A
        // no-op for the HR/ADMIN-only decision methods and for supervisorApprove
        // (assertSupervisorOf already enforced the identical rule before mutation).
        employeeService.assertSelfOrManages(request.getUserId());
        return new LeaveResponse(request.getId(), request.getUserId(), employee.getEmployeeName(),
                request.getLeaveType(), request.getFromDate(), request.getToDate(), request.getDuration(),
                request.getTotalDays(), request.getReason(), request.getStatus(), request.getOrigin(),
                request.getSupervisorId(), request.getApproverId(), request.getApprovalComments(),
                request.getAppliedAt(), request.getDecidedAt(),
                workPolicyResolver.leaveApprovalFlow(employee, request.getFromDate()));
    }

    /**
     * Used only by list-returning queries that have no company filter of
     * their own ({@code findAllByStatus} and friends can span every
     * company). {@code toResponse} resolves the owning employee through the
     * tenant-checked {@code EmployeeService.getEntityByUserId} - without
     * this wrapper, a single other-company row mixed into the result would
     * throw and take the <em>whole</em> list down with it instead of simply
     * being excluded, which is not "isolated," it is "broken."
     */
    private Stream<LeaveResponse> toResponseIfAccessible(LeaveRequest request) {
        try {
            return Stream.of(toResponse(request));
        } catch (NotFoundException notAccessible) {
            return Stream.empty();
        }
    }
}
