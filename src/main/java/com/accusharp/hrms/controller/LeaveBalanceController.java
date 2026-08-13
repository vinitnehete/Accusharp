package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.LeaveBalanceResponse;
import com.accusharp.hrms.enums.LeaveType;
import com.accusharp.hrms.service.leave.LeaveBalanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/leave-balances")
@RequiredArgsConstructor
public class LeaveBalanceController {

    private final LeaveBalanceService leaveBalanceService;

    @PreAuthorize("@authz.can('LEAVE_BALANCE_READ')")
    @GetMapping("/{userId}")
    public List<LeaveBalanceResponse> getBalances(@PathVariable String userId,
                                                  @RequestParam(required = false) Integer year) {
        return leaveBalanceService.getBalances(userId, year == null ? LocalDate.now().getYear() : year);
    }

    @PreAuthorize("@authz.can('LEAVE_BALANCE_MANAGE')")
    @PutMapping("/{userId}")
    public LeaveBalanceResponse setQuota(@PathVariable String userId,
                                         @RequestParam int year,
                                         @RequestParam LeaveType leaveType,
                                         @RequestParam BigDecimal quota) {
        return leaveBalanceService.setQuota(userId, year, leaveType, quota);
    }
}
