package com.accusharp.hrms.service.recovery;

import com.accusharp.hrms.dto.RecoveryAmountRequest;
import com.accusharp.hrms.dto.RecoveryDtos;
import com.accusharp.hrms.dto.RecoveryRequest;
import com.accusharp.hrms.dto.RecoveryUpdateRequest;
import com.accusharp.hrms.entity.Employee;
import com.accusharp.hrms.entity.EmployeeRecovery;
import com.accusharp.hrms.entity.Payroll;
import com.accusharp.hrms.entity.RecoveryTransaction;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.RecoveryStatus;
import com.accusharp.hrms.enums.RecoveryTransactionType;
import com.accusharp.hrms.enums.RecoveryType;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.exception.NotFoundException;
import com.accusharp.hrms.repository.EmployeeRecoveryRepository;
import com.accusharp.hrms.repository.RecoveryTransactionRepository;
import com.accusharp.hrms.service.AuditService;
import com.accusharp.hrms.service.EmployeeService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Loans, salary advances, fines and any other amount an employee owes the
 * company - what was given, what has come back, and what is still outstanding.
 *
 * <p>Before this existed, {@code Payroll.loanDeduction} and {@code
 * Payroll.advanceDeduction} were figures HR retyped every month with nothing
 * behind them: no principal, no running balance, no answer to "how much is
 * left", and nothing for the whole-company {@code generate-all} run to deduct
 * - so a bulk run recovered nothing from anybody. Payroll now reads the
 * balance instead of being told it.
 *
 * <h2>Two things this has to get right</h2>
 *
 * <b>Regeneration must not recover twice.</b> Payroll is immutable: a
 * regeneration supersedes the old revision and inserts a new one rather than
 * editing it. Every repayment therefore records the payroll row that made it,
 * and {@link #reverseForPayroll} gives back exactly those amounts before the
 * new revision is computed. It is a lookup of what was taken, never a
 * re-derivation of what should have been - the two differ the moment
 * affordability, a mid-period salary revision or an attendance correction is
 * involved.
 *
 * <p><b>An unaffordable recovery must not vanish.</b> Recoveries are applied
 * in {@link RecoveryType#recoveryOrder()} against what the period can spare
 * after statutory deductions, never pushing net pay below the configured
 * floor. What doesn't fit stays outstanding and is taken from the next period
 * - which is the whole point of holding a balance rather than a monthly
 * number.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecoveryService {

    private static final int SCALE = 2;
    private static final List<RecoveryStatus> OPEN_STATUSES = List.of(RecoveryStatus.ACTIVE, RecoveryStatus.ON_HOLD);

    private final EmployeeRecoveryRepository recoveryRepository;
    private final RecoveryTransactionRepository transactionRepository;
    private final EmployeeService employeeService;
    private final AuditService auditService;
    private final Validator validator;

    // ---- issuing and maintaining -------------------------------------------

    /**
     * Raises a new recovery and opens its ledger with an {@code ISSUE} row.
     *
     * <p>Tenant-checked through {@link EmployeeService#getEntityByUserId} the
     * same way payroll is, so an employee id from another company yields the
     * same 404 an unknown one would rather than silently creating a loan
     * against it.
     *
     * <p>Registration is independent of attendance and payroll generation.
     * Past, current and future issue dates are accepted; an existing paid
     * period stays unchanged until an explicit payroll regeneration.
     */
    @Transactional
    public RecoveryDtos.RecoveryRow issue(RecoveryRequest request) {
        validate(request);
        Employee employee = recoveryEmployee(request.getEmployeeId());
        employeeService.assertNotSelf(employee.getUserId());
        employeeService.assertManages(employee.getUserId());

        LocalDate issuedOn = request.getIssuedOn() == null ? LocalDate.now() : request.getIssuedOn();
        BigDecimal principal = positiveAmount(request.getPrincipalAmount(), "principalAmount");
        BigDecimal installment = positiveAmount(resolveInstallment(request, principal), "installmentAmount");
        YearMonth start = resolveStart(request, issuedOn);

        if (installment.compareTo(principal) > 0) {
            throw new BusinessRuleException("Instalment " + installment
                    + " cannot exceed the principal " + principal);
        }

        EmployeeRecovery recovery = recoveryRepository.save(EmployeeRecovery.builder()
                .employeeId(employee.getUserId())
                .type(request.getType())
                .status(RecoveryStatus.ACTIVE)
                .principalAmount(principal)
                .installmentAmount(installment)
                .outstandingAmount(principal)
                .startMonth(start.getMonthValue())
                .startYear(start.getYear())
                .issuedOn(issuedOn)
                .reason(request.getReason())
                .remarks(request.getRemarks())
                .issuedBy(request.getIssuedBy())
                .createdAt(Instant.now())
                .build());

        record(recovery, RecoveryTransactionType.ISSUE, principal, BigDecimal.ZERO, principal,
                null, request.getRemarks(), request.getIssuedBy());

        auditService.record("RECOVERY_ISSUE", "EmployeeRecovery", employee.getUserId(), AuditOutcome.SUCCESS,
                request.getType() + " principal=" + principal + " instalment=" + installment
                        + " from=" + start);
        log.info("recovery.issue employeeId={} type={} principal={} instalment={} start={}",
                employee.getUserId(), request.getType(), principal, installment, start);

        return toRow(recovery, employee);
    }

    /**
     * Instalment, in the order a human would expect: whatever was asked for,
     * else a loan's principal spread over its tenure, else the whole
     * principal in one go - which is what a fine or a one-off recovery
     * should do anyway.
     */
    private BigDecimal resolveInstallment(RecoveryRequest request, BigDecimal principal) {
        if (request.getInstallmentAmount() != null) {
            return scaled(request.getInstallmentAmount());
        }
        if (request.getTenureMonths() != null && request.getTenureMonths() > 0) {
            return principal.divide(BigDecimal.valueOf(request.getTenureMonths()), SCALE, RoundingMode.HALF_UP);
        }
        return principal;
    }

    /**
     * A loan disbursed today is repaid from next month's salary unless told
     * otherwise - recovering it from the same month's pay is the exception,
     * not the default. A fine or an advance, by contrast, belongs to the
     * period it was raised in.
     */
    private YearMonth resolveStart(RecoveryRequest request, LocalDate issuedOn) {
        if ((request.getStartMonth() == null) != (request.getStartYear() == null)) {
            throw new BusinessRuleException("startMonth and startYear must be supplied together");
        }
        YearMonth issuedPeriod = YearMonth.from(issuedOn);
        if (request.getStartMonth() != null && request.getStartYear() != null) {
            YearMonth start = YearMonth.of(request.getStartYear(), request.getStartMonth());
            assertStartNotBeforeIssue(start, issuedOn);
            return start;
        }
        return request.getType().isInstallmentBased() ? issuedPeriod.plusMonths(1) : issuedPeriod;
    }

    /** Reschedules or pauses an open recovery. Never touches the balance - see {@link RecoveryUpdateRequest}. */
    @Transactional
    public RecoveryDtos.RecoveryRow update(Long id, RecoveryUpdateRequest request) {
        validate(request);
        EmployeeRecovery recovery = getAccessible(id);
        if (recovery.getStatus() == RecoveryStatus.CLOSED || recovery.getStatus() == RecoveryStatus.CANCELLED) {
            throw new BusinessRuleException("A " + recovery.getStatus() + " recovery cannot be changed");
        }
        if (request.getStatus() != null
                && request.getStatus() != RecoveryStatus.ACTIVE && request.getStatus() != RecoveryStatus.ON_HOLD) {
            throw new BusinessRuleException("Use the close or cancel endpoint to settle a recovery, not a status change");
        }

        if (request.getInstallmentAmount() != null) {
            recovery.setInstallmentAmount(positiveAmount(request.getInstallmentAmount(), "installmentAmount"));
        }
        YearMonth start = YearMonth.of(
                request.getStartYear() == null ? recovery.getStartYear() : request.getStartYear(),
                request.getStartMonth() == null ? recovery.getStartMonth() : request.getStartMonth());
        assertStartNotBeforeIssue(start, recovery.getIssuedOn());
        if (request.getStartMonth() != null) {
            recovery.setStartMonth(request.getStartMonth());
        }
        if (request.getStartYear() != null) {
            recovery.setStartYear(request.getStartYear());
        }
        if (request.getStatus() != null) {
            recovery.setStatus(request.getStatus());
        }
        if (request.getRemarks() != null) {
            recovery.setRemarks(request.getRemarks());
        }
        recovery.setUpdatedAt(Instant.now());

        auditService.record("RECOVERY_UPDATE", "EmployeeRecovery", recovery.getEmployeeId(), AuditOutcome.SUCCESS,
                "id=" + id + " instalment=" + recovery.getInstallmentAmount() + " status=" + recovery.getStatus());
        return toRow(recoveryRepository.save(recovery), null);
    }

    /** Writes off part or all of what is owed - forgiven, never to be recovered. */
    @Transactional
    public RecoveryDtos.RecoveryRow waive(Long id, RecoveryAmountRequest request, String actor) {
        return settle(id, request, actor, RecoveryTransactionType.WAIVER, "RECOVERY_WAIVE");
    }

    /** Records an amount settled outside payroll - cash, a bank transfer, or a wrongly-entered principal being corrected. */
    @Transactional
    public RecoveryDtos.RecoveryRow adjust(Long id, RecoveryAmountRequest request, String actor) {
        return settle(id, request, actor, RecoveryTransactionType.ADJUSTMENT, "RECOVERY_ADJUST");
    }

    private RecoveryDtos.RecoveryRow settle(Long id, RecoveryAmountRequest request, String actor,
                                            RecoveryTransactionType type, String auditAction) {
        validate(request);
        EmployeeRecovery recovery = getAccessible(id);
        if (recovery.getStatus() == RecoveryStatus.CANCELLED) {
            throw new BusinessRuleException("A cancelled recovery has no balance to settle");
        }
        BigDecimal amount = positiveAmount(request.getAmount(), "amount");
        if (amount.compareTo(recovery.getOutstandingAmount()) > 0) {
            throw new BusinessRuleException(amount + " exceeds the " + recovery.getOutstandingAmount()
                    + " still outstanding");
        }

        BigDecimal before = recovery.getOutstandingAmount();
        BigDecimal after = before.subtract(amount).setScale(SCALE, RoundingMode.HALF_UP);
        recovery.setOutstandingAmount(after);
        recovery.setUpdatedAt(Instant.now());
        closeIfSettled(recovery);

        record(recovery, type, amount, before, after, null, request.getRemarks(), actor);
        auditService.record(auditAction, "EmployeeRecovery", recovery.getEmployeeId(), AuditOutcome.SUCCESS,
                "id=" + id + " amount=" + amount + " outstanding=" + after);
        return toRow(recoveryRepository.save(recovery), null);
    }

    /**
     * Cancels a recovery raised in error. The row is kept rather than deleted
     * - so does its ledger - because a loan that "never existed" is exactly
     * the kind of thing an audit needs to see was withdrawn, by whom and when.
     */
    @Transactional
    public RecoveryDtos.RecoveryRow cancel(Long id, String remarks, String actor) {
        EmployeeRecovery recovery = getAccessible(id);
        if (recovery.getStatus() == RecoveryStatus.CANCELLED) {
            return toRow(recovery, null);
        }
        BigDecimal recovered = recovery.getPrincipalAmount().subtract(recovery.getOutstandingAmount());
        if (recovered.signum() > 0) {
            throw new BusinessRuleException(recovered + " has already been recovered against this "
                    + recovery.getType() + " - waive the remaining balance instead of cancelling it");
        }

        BigDecimal before = recovery.getOutstandingAmount();
        recovery.setOutstandingAmount(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
        recovery.setStatus(RecoveryStatus.CANCELLED);
        recovery.setClosedAt(Instant.now());
        recovery.setUpdatedAt(Instant.now());

        record(recovery, RecoveryTransactionType.ADJUSTMENT, before, before, BigDecimal.ZERO,
                null, remarks == null ? "Cancelled" : remarks, actor);
        auditService.record("RECOVERY_CANCEL", "EmployeeRecovery", recovery.getEmployeeId(), AuditOutcome.SUCCESS,
                "id=" + id + " outstanding=" + before);
        return toRow(recoveryRepository.save(recovery), null);
    }

    // ---- reads --------------------------------------------------------------

    /**
     * Every recovery an employee has. Tenant-checked and self-service scoped
     * the same way {@code PayrollService.getHistory} is: an employee sees
     * their own, a supervisor their team's, HR/ADMIN their company's.
     */
    @Transactional(readOnly = true)
    public List<RecoveryDtos.RecoveryRow> getForEmployee(String employeeId) {
        Employee employee = recoveryEmployee(employeeId);
        employeeService.assertSelfOrManages(employee.getUserId());
        employeeService.assertCanSeePayOf(employee.getUserId());
        return recoveryRepository.findAllByEmployeeIdOrderByIssuedOnDescIdDesc(employee.getUserId()).stream()
                .map(recovery -> toRow(recovery, employee))
                .toList();
    }

    /** Balances by type, every recovery and the full ledger behind them - the "where did this number come from" view. */
    @Transactional(readOnly = true)
    public RecoveryDtos.Statement getStatement(String employeeId) {
        Employee employee = recoveryEmployee(employeeId);
        employeeService.assertSelfOrManages(employee.getUserId());
        employeeService.assertCanSeePayOf(employee.getUserId());

        List<EmployeeRecovery> recoveries =
                recoveryRepository.findAllByEmployeeIdOrderByIssuedOnDescIdDesc(employee.getUserId());
        List<RecoveryDtos.TypeBalance> balances = balancesOf(recoveries, null);

        return new RecoveryDtos.Statement(
                employee.getUserId(),
                employee.getEmployeeCode(),
                employee.getEmployeeName(),
                balances,
                total(balances),
                recoveries.stream().map(recovery -> toRow(recovery, employee)).toList(),
                transactionRepository.findAllByEmployeeIdOrderByRecordedAtDescIdDesc(employee.getUserId()).stream()
                        .map(this::toTransactionRow).toList());
    }

    /**
     * What payroll would recover for one employee in one period, per type -
     * the figures the generate screen pre-fills, so HR runs payroll seeing
     * the numbers that are about to apply instead of typing them from memory.
     *
     * <p>Scheduled, not affordable: whether the month can actually cover it
     * depends on earnings and statutory deductions the payroll hasn't
     * computed yet. {@link #plan} is where affordability is decided.
     */
    @Transactional(readOnly = true)
    public RecoveryDtos.Schedule getSchedule(String employeeId, int month, int year) {
        Employee employee = recoveryEmployee(employeeId);
        employeeService.assertSelfOrManages(employee.getUserId());
        employeeService.assertCanSeePayOf(employee.getUserId());

        YearMonth period = YearMonth.of(year, month);
        List<EmployeeRecovery> open = recoveryRepository.findAllByEmployeeIdAndStatusIn(employee.getUserId(), OPEN_STATUSES);
        List<RecoveryDtos.TypeBalance> balances = balancesOf(open, period);

        Map<RecoveryType, BigDecimal> scheduled = balances.stream().collect(Collectors.toMap(
                RecoveryDtos.TypeBalance::type, RecoveryDtos.TypeBalance::scheduledInstallment));

        BigDecimal advance = scheduled.getOrDefault(RecoveryType.ADVANCE, zero());
        BigDecimal loan = scheduled.getOrDefault(RecoveryType.LOAN, zero());
        BigDecimal fine = scheduled.getOrDefault(RecoveryType.FINE, zero());
        BigDecimal other = scheduled.getOrDefault(RecoveryType.OTHER, zero());

        return new RecoveryDtos.Schedule(employee.getUserId(), month, year, balances,
                advance, loan, fine, other,
                advance.add(loan).add(fine).add(other).setScale(SCALE, RoundingMode.HALF_UP),
                total(balances));
    }

    /**
     * Every open recovery across the caller's company - the register HR works
     * from before a payroll run.
     *
     * <p>Scoped through {@link EmployeeService#getAllEntities()} rather than
     * {@code getActiveEntities()} for the same reason payroll's own period
     * query is: a leaver's outstanding loan is exactly what a full and final
     * settlement needs to see, and deactivating them must not hide it.
     */
    @Transactional(readOnly = true)
    public List<RecoveryDtos.RecoveryRow> getOutstanding(boolean includeSettled) {
        Predicate<String> seesPay = employeeService.payVisibility();
        Map<String, Employee> employees = employeeService.getAllEntities().stream()
                .filter(employee -> employeeService.isSelfOrManages(employee.getUserId()))
                .filter(employee -> seesPay.test(employee.getUserId()))
                .collect(Collectors.toMap(Employee::getUserId, Function.identity(), (a, b) -> a));

        List<EmployeeRecovery> recoveries = includeSettled
                ? recoveryRepository.findAllByEmployeeIdIn(employees.keySet())
                : recoveryRepository.findAllByEmployeeIdInAndStatusIn(employees.keySet(), OPEN_STATUSES);

        return recoveries.stream()
                .sorted(Comparator.comparing(EmployeeRecovery::getEmployeeId)
                        .thenComparing(EmployeeRecovery::getType)
                        .thenComparing(EmployeeRecovery::getId))
                .map(recovery -> toRow(recovery, employees.get(recovery.getEmployeeId())))
                .toList();
    }

    /**
     * Total outstanding per employee across every type, for a batch of them -
     * one {@code IN}-clause query rather than one per employee, the same shape
     * as {@code PayrollService.pendingGenerationEmployeeIds}. Feeds the payroll
     * debug endpoint, which reads a whole period at once and would otherwise
     * issue a query per row.
     *
     * <p>Deliberately unscoped: every caller has already resolved and
     * tenant-checked the employees it is asking about.
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> outstandingByEmployee(Collection<String> employeeIds) {
        if (employeeIds.isEmpty()) {
            return Map.of();
        }
        return recoveryRepository.findAllByEmployeeIdInAndStatusIn(employeeIds, OPEN_STATUSES).stream()
                .collect(Collectors.groupingBy(EmployeeRecovery::getEmployeeId,
                        Collectors.reducing(zero(), EmployeeRecovery::getOutstandingAmount, BigDecimal::add)));
    }

    // ---- what payroll calls -------------------------------------------------

    /**
     * Decides what this period recovers, without writing anything.
     *
     * <p>Each type takes {@code requested} - the caller's per-period override
     * when they gave one, otherwise the scheduled instalments - and the types
     * are then served in {@link RecoveryType#recoveryOrder()} out of one
     * shared {@code budget}: what the period can spare once statutory and
     * fixed deductions are paid and the net-pay floor is respected. A type
     * that doesn't fit is recovered partially or not at all, and the
     * remainder simply stays outstanding for next period.
     *
     * <p>Requested amounts beyond what any recovery on file backs are carried
     * as {@code adHocRequested}: a fine typed straight onto the generate
     * screen with nothing raised behind it still deducts, and if the month
     * cannot afford it {@link #commit} raises a recovery for the shortfall so
     * it is taken next period rather than quietly lost.
     *
     * @param requested per-type override, or a null value for "use the schedule"
     */
    @Transactional(readOnly = true)
    public RecoveryPlan plan(String employeeId, YearMonth period,
                             Map<RecoveryType, BigDecimal> requested, BigDecimal budget) {
        Map<RecoveryType, List<EmployeeRecovery>> byType =
                recoveryRepository.findAllByEmployeeIdAndStatusIn(employeeId, OPEN_STATUSES).stream()
                        .sorted(Comparator.comparing(EmployeeRecovery::getIssuedOn)
                                .thenComparing(EmployeeRecovery::getId))
                        .collect(Collectors.groupingBy(EmployeeRecovery::getType, LinkedHashMap::new,
                                Collectors.toList()));

        BigDecimal remainingBudget = budget == null ? zero() : budget.max(BigDecimal.ZERO);
        Map<RecoveryType, RecoveryPlan.TypeOutcome> outcomes = new EnumMap<>(RecoveryType.class);

        for (RecoveryType type : orderedTypes()) {
            List<EmployeeRecovery> open = byType.getOrDefault(type, List.of());
            List<EmployeeRecovery> due = open.stream()
                    .filter(recovery -> recovery.isRecoverableIn(period)).toList();
            BigDecimal opening = open.stream()
                    .map(EmployeeRecovery::getOutstandingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE, RoundingMode.HALF_UP);

            BigDecimal override = requested == null ? null : requested.get(type);
            BigDecimal asked = override != null
                    ? scaled(override)
                    : due.stream().map(EmployeeRecovery::scheduledInstallment)
                            .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE, RoundingMode.HALF_UP);

            // The share the ledger actually backs, and the share it doesn't -
            // the second is an ad-hoc amount for this period only.
            BigDecimal recoverable = due.stream().map(EmployeeRecovery::getOutstandingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ledgerAsked = asked.min(recoverable);
            BigDecimal adHocAsked = asked.subtract(ledgerAsked).max(BigDecimal.ZERO);

            List<RecoveryPlan.Allocation> allocations = new ArrayList<>();
            BigDecimal ledgerApplied = BigDecimal.ZERO;
            BigDecimal stillToAllocate = ledgerAsked.min(remainingBudget);
            for (EmployeeRecovery recovery : due) {
                if (stillToAllocate.signum() <= 0) {
                    break;
                }
                BigDecimal cap = override == null ? recovery.scheduledInstallment() : recovery.getOutstandingAmount();
                BigDecimal share = cap.min(stillToAllocate)
                        .setScale(SCALE, RoundingMode.HALF_UP);
                if (share.signum() <= 0) {
                    continue;
                }
                allocations.add(new RecoveryPlan.Allocation(recovery, share));
                ledgerApplied = ledgerApplied.add(share);
                stillToAllocate = stillToAllocate.subtract(share);
            }
            remainingBudget = remainingBudget.subtract(ledgerApplied);

            BigDecimal adHocApplied = adHocAsked.min(remainingBudget).max(BigDecimal.ZERO)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            remainingBudget = remainingBudget.subtract(adHocApplied);

            BigDecimal applied = ledgerApplied.add(adHocApplied).setScale(SCALE, RoundingMode.HALF_UP);
            outcomes.put(type, new RecoveryPlan.TypeOutcome(
                    type, opening, asked, applied,
                    opening.subtract(ledgerApplied).setScale(SCALE, RoundingMode.HALF_UP),
                    adHocAsked.setScale(SCALE, RoundingMode.HALF_UP), adHocApplied,
                    List.copyOf(allocations)));
        }
        return new RecoveryPlan(outcomes);
    }

    /**
     * Moves the balances a {@link #plan} decided, now that the payroll row
     * exists to point the ledger at.
     *
     * <p>Also raises a recovery for any ad-hoc amount the period could not
     * afford - a ₹2,000 fine of which only ₹800 fitted leaves ₹1,200 genuinely
     * still owed, and dropping it because nothing was on file to hold it is
     * the bug this whole class exists to fix. The new recovery starts the
     * following period and carries the payroll that created it, so
     * {@link #reverseForPayroll} can withdraw it again if that payroll is
     * later superseded.
     */
    @Transactional
    public void commit(Payroll payroll, RecoveryPlan plan, String actor) {
        for (RecoveryType type : orderedTypes()) {
            RecoveryPlan.TypeOutcome outcome = plan.outcome(type);
            if (outcome == null) {
                continue;
            }
            for (RecoveryPlan.Allocation allocation : outcome.allocations()) {
                EmployeeRecovery recovery = allocation.recovery();
                BigDecimal before = recovery.getOutstandingAmount();
                BigDecimal after = before.subtract(allocation.amount()).setScale(SCALE, RoundingMode.HALF_UP);
                recovery.setOutstandingAmount(after);
                recovery.setUpdatedAt(Instant.now());
                closeIfSettled(recovery);
                recoveryRepository.save(recovery);

                record(recovery, RecoveryTransactionType.REPAYMENT, allocation.amount(), before, after,
                        payroll, "Recovered by payroll " + payroll.getMonth() + "/" + payroll.getYear(), actor);
            }

            BigDecimal shortfall = outcome.adHocShortfall();
            if (shortfall.signum() > 0) {
                carryForward(payroll, type, shortfall, actor);
            }
        }

        BigDecimal applied = plan.totalApplied();
        if (applied.signum() > 0 || plan.totalCarriedForward().signum() > 0) {
            log.info("recovery.commit employeeId={} period={}/{} revision={} recovered={} carriedForward={}",
                    payroll.getEmployeeId(), payroll.getMonth(), payroll.getYear(), payroll.getRevision(),
                    applied, plan.totalCarriedForward());
        }
    }

    /** Raises the unaffordable part of an ad-hoc deduction as a recovery, so the next period takes it. */
    private void carryForward(Payroll payroll, RecoveryType type, BigDecimal amount, String actor) {
        YearMonth next = YearMonth.of(payroll.getYear(), payroll.getMonth()).plusMonths(1);
        String note = "Carried forward from " + payroll.getMonth() + "/" + payroll.getYear()
                + " - the period could not cover the full amount";

        EmployeeRecovery recovery = recoveryRepository.save(EmployeeRecovery.builder()
                .employeeId(payroll.getEmployeeId())
                .type(type)
                .status(RecoveryStatus.ACTIVE)
                .principalAmount(amount)
                .installmentAmount(amount)
                .outstandingAmount(amount)
                .startMonth(next.getMonthValue())
                .startYear(next.getYear())
                .issuedOn(LocalDate.now())
                .reason(note)
                .issuedBy(actor)
                .createdAt(Instant.now())
                .build());

        record(recovery, RecoveryTransactionType.ISSUE, amount, BigDecimal.ZERO, amount, payroll, note, actor);
        log.info("recovery.carryForward employeeId={} type={} amount={} from={}/{} recoverFrom={}",
                payroll.getEmployeeId(), type, amount, payroll.getMonth(), payroll.getYear(), next);
    }

    /**
     * Gives back everything a now-superseded payroll recovered, so the
     * revision replacing it starts from the balance that was there before it
     * ran. Without this, every regeneration would recover the instalment a
     * second time - and a CSV bulk run with {@code regenerate=true} would do
     * it across the whole company at once.
     *
     * <p>Reverses the recorded amounts rather than recomputing them: what a
     * period actually took depends on what it could afford, which a later
     * attendance correction or salary revision changes.
     *
     * <p>Also withdraws any recovery that payroll <em>created</em> - the
     * carry-forward of an ad-hoc amount it couldn't afford - but only while
     * nothing has been recovered against it yet. One that a later period has
     * already started repaying is left alone and flagged: undoing it would
     * silently erase money that has since come back.
     */
    @Transactional
    public void reverseForPayroll(Payroll payroll, String actor) {
        for (RecoveryTransaction repayment : transactionRepository.findAllByPayrollIdAndType(
                payroll.getId(), RecoveryTransactionType.REPAYMENT)) {
            EmployeeRecovery recovery = repayment.getRecovery();
            BigDecimal before = recovery.getOutstandingAmount();
            BigDecimal after = before.add(repayment.getAmount()).setScale(SCALE, RoundingMode.HALF_UP);

            recovery.setOutstandingAmount(after);
            recovery.setUpdatedAt(Instant.now());
            // A recovery closed by that repayment is open again now that the
            // money has been given back - the balance and the status have to
            // move together or payroll will skip a loan that is owed.
            if (recovery.getStatus() == RecoveryStatus.CLOSED && after.signum() > 0) {
                recovery.setStatus(RecoveryStatus.ACTIVE);
                recovery.setClosedAt(null);
            }
            recoveryRepository.save(recovery);

            record(recovery, RecoveryTransactionType.REVERSAL, repayment.getAmount(), before, after, payroll,
                    "Reversed - payroll " + payroll.getMonth() + "/" + payroll.getYear()
                            + " revision " + payroll.getRevision() + " was superseded", actor);
        }

        for (RecoveryTransaction issued : transactionRepository.findAllByPayrollIdAndType(
                payroll.getId(), RecoveryTransactionType.ISSUE)) {
            EmployeeRecovery recovery = issued.getRecovery();
            if (recovery.getStatus() == RecoveryStatus.CANCELLED) {
                continue;
            }
            if (recovery.getOutstandingAmount().compareTo(recovery.getPrincipalAmount()) != 0) {
                log.warn("recovery.reverse.skipped recoveryId={} employeeId={} - carried-forward amount is "
                                + "already partly recovered, leaving it open",
                        recovery.getId(), recovery.getEmployeeId());
                continue;
            }
            BigDecimal before = recovery.getOutstandingAmount();
            recovery.setOutstandingAmount(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
            recovery.setStatus(RecoveryStatus.CANCELLED);
            recovery.setClosedAt(Instant.now());
            recovery.setUpdatedAt(Instant.now());
            recoveryRepository.save(recovery);

            record(recovery, RecoveryTransactionType.ADJUSTMENT, before, before, BigDecimal.ZERO, payroll,
                    "Withdrawn - the payroll that carried this amount forward was superseded", actor);
        }
    }

    // ---- helpers ------------------------------------------------------------

    /** Types in the order a short month serves them - see {@link RecoveryType#recoveryOrder()}. */
    public static List<RecoveryType> orderedTypes() {
        return java.util.Arrays.stream(RecoveryType.values())
                .sorted(Comparator.comparingInt(RecoveryType::recoveryOrder))
                .toList();
    }

    private EmployeeRecovery getAccessible(Long id) {
        EmployeeRecovery recovery = recoveryRepository.findById(id)
                .orElseThrow(() -> NotFoundException.of("Recovery", id));
        // Resolving the owner is what enforces tenancy - a recovery id from
        // another company yields the same 404 an unknown id would, exactly as
        // PayrollService.getById does.
        Employee employee = recoveryEmployee(recovery.getEmployeeId());
        employeeService.assertNotSelf(employee.getUserId());
        employeeService.assertManages(employee.getUserId());
        return recovery;
    }

    private Employee recoveryEmployee(String userId) {
        Employee employee = employeeService.getEntityByUserId(userId);
        if (employee.isContractorWorker()) {
            throw NotFoundException.of("Employee", "userId " + employee.getUserId());
        }
        employeeService.assertNotCompanyAccount(employee);
        return employee;
    }

    /** Shared by single and bulk issuance, which otherwise bypasses the controller's bean validation. */
    private <T> void validate(T request) {
        String errors = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .sorted().collect(Collectors.joining("; "));
        if (!errors.isEmpty()) {
            throw new BusinessRuleException(errors);
        }
    }

    private static BigDecimal positiveAmount(BigDecimal value, String field) {
        BigDecimal amount = scaled(value);
        if (amount.signum() <= 0) {
            throw new BusinessRuleException(field + " must be at least 0.01 after rounding");
        }
        return amount;
    }

    private static void assertStartNotBeforeIssue(YearMonth start, LocalDate issuedOn) {
        if (start.isBefore(YearMonth.from(issuedOn))) {
            throw new BusinessRuleException("Recovery cannot start before its issue month " + YearMonth.from(issuedOn));
        }
    }

    private void closeIfSettled(EmployeeRecovery recovery) {
        if (recovery.getOutstandingAmount().signum() <= 0 && OPEN_STATUSES.contains(recovery.getStatus())) {
            recovery.setStatus(RecoveryStatus.CLOSED);
            recovery.setClosedAt(Instant.now());
        }
    }

    private void record(EmployeeRecovery recovery, RecoveryTransactionType type, BigDecimal amount,
                        BigDecimal before, BigDecimal after, Payroll payroll, String remarks, String actor) {
        transactionRepository.save(RecoveryTransaction.builder()
                .recovery(recovery)
                .employeeId(recovery.getEmployeeId())
                .type(type)
                .amount(scaled(amount))
                .balanceBefore(scaled(before))
                .balanceAfter(scaled(after))
                .payrollId(payroll == null ? null : payroll.getId())
                .month(payroll == null ? null : payroll.getMonth())
                .year(payroll == null ? null : payroll.getYear())
                .payrollRevision(payroll == null ? null : payroll.getRevision())
                .remarks(remarks)
                .recordedBy(actor)
                .recordedAt(Instant.now())
                .build());
    }

    /**
     * One balance per type, always all four - a zero row is the answer to
     * "does this employee have a loan?", so omitting it would make the UI
     * guess between "none" and "not loaded".
     *
     * @param period when given, only recoveries payroll may recover from in
     *               that period contribute a scheduled instalment
     */
    private List<RecoveryDtos.TypeBalance> balancesOf(List<EmployeeRecovery> recoveries, YearMonth period) {
        return orderedTypes().stream().map(type -> {
            List<EmployeeRecovery> open = recoveries.stream()
                    .filter(recovery -> recovery.getType() == type && OPEN_STATUSES.contains(recovery.getStatus()))
                    .toList();
            BigDecimal scheduled = open.stream()
                    .filter(recovery -> recovery.getStatus() == RecoveryStatus.ACTIVE
                            && (period == null || recovery.isRecoverableIn(period)))
                    .map(EmployeeRecovery::scheduledInstallment)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE, RoundingMode.HALF_UP);
            return new RecoveryDtos.TypeBalance(type, open.size(),
                    sum(open, EmployeeRecovery::getPrincipalAmount),
                    sum(open, EmployeeRecovery::getOutstandingAmount),
                    scheduled);
        }).toList();
    }

    private BigDecimal total(List<RecoveryDtos.TypeBalance> balances) {
        return balances.stream().map(RecoveryDtos.TypeBalance::outstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<EmployeeRecovery> recoveries, Function<EmployeeRecovery, BigDecimal> field) {
        return recoveries.stream().map(field).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private RecoveryDtos.RecoveryRow toRow(EmployeeRecovery recovery, Employee employee) {
        BigDecimal recovered = recovery.getPrincipalAmount().subtract(recovery.getOutstandingAmount())
                .setScale(SCALE, RoundingMode.HALF_UP);
        Integer remaining = recovery.getInstallmentAmount().signum() <= 0
                ? null
                : recovery.getOutstandingAmount()
                        .divide(recovery.getInstallmentAmount(), 0, RoundingMode.CEILING).intValue();

        return new RecoveryDtos.RecoveryRow(
                recovery.getId(), recovery.getEmployeeId(),
                employee == null ? null : employee.getEmployeeCode(),
                employee == null ? null : employee.getEmployeeName(),
                employee == null || employee.getDepartment() == null
                        ? null : employee.getDepartment().getDepartmentName(),
                recovery.getType(), recovery.getStatus(),
                recovery.getPrincipalAmount(), recovery.getInstallmentAmount(), recovered,
                recovery.getOutstandingAmount(),
                recovery.getStartMonth(), recovery.getStartYear(), recovery.getIssuedOn(),
                recovery.getReason(), recovery.getRemarks(), recovery.getIssuedBy(),
                recovery.getCreatedAt(), recovery.getClosedAt(), remaining);
    }

    private RecoveryDtos.TransactionRow toTransactionRow(RecoveryTransaction transaction) {
        return new RecoveryDtos.TransactionRow(
                transaction.getId(), transaction.getRecovery().getId(), transaction.getRecovery().getType(),
                transaction.getType(), transaction.getAmount(), transaction.getBalanceBefore(),
                transaction.getBalanceAfter(), transaction.getPayrollId(), transaction.getMonth(),
                transaction.getYear(), transaction.getPayrollRevision(), transaction.getRemarks(),
                transaction.getRecordedBy(), transaction.getRecordedAt());
    }

    private static BigDecimal scaled(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
