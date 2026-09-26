package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.LeaveBalanceResponse;
import com.accusharp.hrms.dto.LeaveYearCloseRow;
import com.accusharp.hrms.entity.LeaveCredit;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.leave.LeaveBalanceService;
import com.accusharp.hrms.service.leave.LeaveYearCloseService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/leave-balances")
@RequiredArgsConstructor
public class LeaveBalanceController {

    private final LeaveBalanceService leaveBalanceService;
    private final LeaveYearCloseService leaveYearCloseService;

    @PreAuthorize("@authz.can('LEAVE_BALANCE_READ')")
    @GetMapping("/{userId}")
    public List<LeaveBalanceResponse> getBalances(@PathVariable String userId,
                                                  @RequestParam(required = false) Integer year) {
        return leaveBalanceService.getBalances(userId, year == null ? leaveBalanceService.currentLeaveYear(userId) : year);
    }

    @PreAuthorize("@authz.can('LEAVE_BALANCE_MANAGE')")
    @PutMapping("/{userId}")
    public LeaveBalanceResponse setQuota(@PathVariable String userId,
                                         @RequestParam @Min(2000) @Max(2100) int year,
                                         @RequestParam LeaveType leaveType,
                                         @RequestParam BigDecimal quota) {
        return leaveBalanceService.setQuota(userId, year, leaveType, quota);
    }

    /**
     * Every earned-leave credit and carry-forward posted onto the year, with the
     * reason for each. Readable by the employee themselves - it is what answers
     * "why did I get 1.2 this month?".
     */
    @PreAuthorize("@authz.can('LEAVE_BALANCE_READ')")
    @GetMapping("/{userId}/credits")
    public List<LeaveCredit> getCredits(@PathVariable String userId,
                                        @RequestParam(required = false) Integer year) {
        return leaveBalanceService.getCredits(userId, year == null ? leaveBalanceService.currentLeaveYear(userId) : year);
    }

    /**
     * Carries the year's balances into the next one, up to each rule's cap, and
     * returns what carried and what is over the cap for payout. Run once
     * December's payroll is done; safe to run again.
     */
    @PreAuthorize("@authz.can('LEAVE_BALANCE_MANAGE')")
    @PostMapping("/close-year")
    public List<LeaveYearCloseRow> closeYear(@RequestParam @Min(2000) @Max(2100) int year,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return leaveYearCloseService.closeYear(year, principal == null ? null : principal.getUsername());
    }
}
