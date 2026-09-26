package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.LeaveSettingsRequest;
import com.accusharp.hrms.dto.LeaveSettingsResponse;
import com.accusharp.hrms.service.leave.LeaveSettingsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's company's leave year. Readable by everyone in the company - the
 * balances page needs it to open on the right year - and changed only by HR.
 */
@RestController
@RequestMapping("/api/leave-settings")
@RequiredArgsConstructor
public class LeaveSettingsController {

    private final LeaveSettingsService leaveSettingsService;

    @PreAuthorize("@authz.can('LEAVE_BALANCE_READ')")
    @GetMapping
    public LeaveSettingsResponse get() {
        return leaveSettingsService.get();
    }

    @PreAuthorize("@authz.can('LEAVE_BALANCE_MANAGE')")
    @PutMapping
    public LeaveSettingsResponse update(@Valid @RequestBody LeaveSettingsRequest request) {
        return leaveSettingsService.update(request);
    }
}
