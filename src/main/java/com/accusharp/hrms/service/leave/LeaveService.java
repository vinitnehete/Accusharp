package com.accusharp.hrms.service.leave;

import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.LeaveResponse;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.LeaveRequest;
import com.accusharp.hrms.enums.LeaveDuration;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.enums.Role;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.LeaveRequestRepository;
import com.accusharp.hrms.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    @Transactional
    public LeaveResponse apply(LeaveRequestPayload payload) {
        Employee employee = employeeService.getEntityByUserId(payload.getUserId());

        if (payload.getFromDate().isAfter(payload.getToDate())) {
            throw new BusinessRuleException("fromDate must be on or before toDate");
        }
        if (payload.getDuration() != LeaveDuration.FULL_DAY
                && !payload.getFromDate().equals(payload.getToDate())) {
            throw new BusinessRuleException("A half day leave must start and end on the same date");
        }
        if (payload.getFromDate().getYear() != payload.getToDate().getYear()) {
            throw new BusinessRuleException("A leave request cannot span two calendar years - split it");
        }
        assertNoOverlap(payload.getUserId(), payload.getFromDate(), payload.getToDate());

        BigDecimal totalDays = leaveCalculationService.countDays(
                payload.getFromDate(), payload.getToDate(), payload.getDuration());

        // Fail early rather than letting the approver hit an empty balance.
        assertBalanceAvailable(payload, totalDays);

        LeaveRequest request = LeaveRequest.builder()
                .userId(payload.getUserId())
                .leaveType(payload.getLeaveType())
                .fromDate(payload.getFromDate())
                .toDate(payload.getToDate())
                .duration(payload.getDuration())
                .totalDays(totalDays)
                .reason(payload.getReason())
                .status(LeaveStatus.PENDING)
                .supervisorId(employee.getSupervisor() == null ? null : employee.getSupervisor().getUserId())
                .appliedAt(Instant.now())
                .build();

        log.info("leave.apply userId={} type={} from={} to={} days={}", payload.getUserId(),
                payload.getLeaveType(), payload.getFromDate(), payload.getToDate(), totalDays);
        return toResponse(leaveRequestRepository.save(request));
    }

    /** Step one: the employee's own supervisor endorses the request. */
    @Transactional
    public LeaveResponse supervisorApprove(Long id, LeaveDecisionRequest decision) {
        LeaveRequest request = getEntity(id);
        assertStatus(request, LeaveStatus.PENDING);
        assertSupervisorOf(decision.getApproverId(), request.getUserId());

        request.setStatus(LeaveStatus.SUPERVISOR_APPROVED);
        request.setApproverId(decision.getApproverId());
        request.setApprovalComments(decision.getComments());
        return toResponse(leaveRequestRepository.save(request));
    }

    /** Step two: HR gives final approval, which is what consumes balance. */
    @Transactional
    public LeaveResponse approve(Long id, LeaveDecisionRequest decision) {
        LeaveRequest request = getEntity(id);
        if (!request.getStatus().isOpen()) {
            throw new BusinessRuleException("Leave is already " + request.getStatus());
        }
        assertHrOrAdmin(decision.getApproverId());

        leaveBalanceService.consume(request.getUserId(), request.getFromDate().getYear(),
                request.getLeaveType(), request.getTotalDays());

        request.setStatus(LeaveStatus.APPROVED);
        request.setApproverId(decision.getApproverId());
        request.setApprovalComments(decision.getComments());
        request.setDecidedAt(Instant.now());

        log.info("leave.approve id={} userId={} days={}", id, request.getUserId(), request.getTotalDays());
        return toResponse(leaveRequestRepository.save(request));
    }

    /** Rejection is a dead end - balance is untouched. */
    @Transactional
    public LeaveResponse reject(Long id, LeaveDecisionRequest decision) {
        LeaveRequest request = getEntity(id);
        if (!request.getStatus().isOpen()) {
            throw new BusinessRuleException("Leave is already " + request.getStatus());
        }
        request.setStatus(LeaveStatus.REJECTED);
        request.setApproverId(decision.getApproverId());
        request.setApprovalComments(decision.getComments());
        request.setDecidedAt(Instant.now());
        return toResponse(leaveRequestRepository.save(request));
    }

    /** Cancelling an approved leave gives the days back. */
    @Transactional
    public LeaveResponse cancel(Long id, LeaveDecisionRequest decision) {
        LeaveRequest request = getEntity(id);
        if (request.getStatus() == LeaveStatus.CANCELLED || request.getStatus() == LeaveStatus.REJECTED) {
            throw new BusinessRuleException("Leave is already " + request.getStatus());
        }
        if (request.getStatus().consumesBalance()) {
            leaveBalanceService.restore(request.getUserId(), request.getFromDate().getYear(),
                    request.getLeaveType(), request.getTotalDays());
        }
        request.setStatus(LeaveStatus.CANCELLED);
        request.setApproverId(decision.getApproverId());
        request.setApprovalComments(decision.getComments());
        request.setDecidedAt(Instant.now());
        return toResponse(leaveRequestRepository.save(request));
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
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveResponse> getByStatus(LeaveStatus status) {
        return leaveRequestRepository.findAllByStatus(status).stream().map(this::toResponse).toList();
    }

    /** Every approved leave overlapping a window - the leave calendar. */
    @Transactional(readOnly = true)
    public List<LeaveResponse> getCalendar(LocalDate fromDate, LocalDate toDate) {
        return leaveRequestRepository
                .findAllByStatusInAndFromDateLessThanEqualAndToDateGreaterThanEqual(
                        List.of(LeaveStatus.APPROVED), toDate, fromDate)
                .stream().map(this::toResponse).toList();
    }

    // ---- helpers -----------------------------------------------------------

    private LeaveRequest getEntity(Long id) {
        return leaveRequestRepository.findById(id).orElseThrow(() -> NotFoundException.of("Leave request", id));
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

    private void assertBalanceAvailable(LeaveRequestPayload payload, BigDecimal totalDays) {
        if (!payload.getLeaveType().isPaid()) {
            return;
        }
        BigDecimal available = leaveBalanceService
                .getOrCreate(payload.getUserId(), payload.getFromDate().getYear(), payload.getLeaveType())
                .available();
        if (available.compareTo(totalDays) < 0) {
            throw new BusinessRuleException("Insufficient " + payload.getLeaveType() + " balance: available "
                    + available + ", requested " + totalDays);
        }
    }

    private void assertSupervisorOf(String approverId, String userId) {
        Employee approver = employeeService.getEntityByUserId(approverId);
        if (approver.getRole() == Role.ADMIN || approver.getRole() == Role.HR) {
            return;
        }
        if (!employeeService.supervises(approverId, userId)) {
            throw new BusinessRuleException("Approver " + approverId + " does not supervise " + userId);
        }
    }

    private void assertHrOrAdmin(String approverId) {
        Employee approver = employeeService.getEntityByUserId(approverId);
        if (approver.getRole() != Role.HR && approver.getRole() != Role.ADMIN) {
            throw new BusinessRuleException("Final leave approval requires the HR or ADMIN role");
        }
    }

    private LeaveResponse toResponse(LeaveRequest request) {
        String employeeName = employeeService.getEntityByUserId(request.getUserId()).getEmployeeName();
        return new LeaveResponse(request.getId(), request.getUserId(), employeeName, request.getLeaveType(),
                request.getFromDate(), request.getToDate(), request.getDuration(), request.getTotalDays(),
                request.getReason(), request.getStatus(), request.getSupervisorId(), request.getApproverId(),
                request.getApprovalComments(), request.getAppliedAt(), request.getDecidedAt());
    }
}
