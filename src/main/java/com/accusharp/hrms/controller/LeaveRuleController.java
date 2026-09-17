package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.LeaveRuleRequest;
import com.accusharp.hrms.entity.LeaveRule;
import com.accusharp.hrms.service.leave.LeaveRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Who gets which leave, per employment type - see {@link LeaveRule}.
 *
 * <p>All of it needs {@code LEAVE_BALANCE_MANAGE}, reading included: a rule
 * decides how much paid leave a whole population gets, which is the same
 * authority overriding one balance already needs.
 */
@RestController
@RequestMapping("/api/leave-rules")
@RequiredArgsConstructor
@PreAuthorize("@authz.can('LEAVE_BALANCE_MANAGE')")
public class LeaveRuleController {

    private final LeaveRuleService leaveRuleService;

    /** The caller's own company's rules plus the shared ones. */
    @GetMapping
    public List<LeaveRule> getAll() {
        return leaveRuleService.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRule create(@Valid @RequestBody LeaveRuleRequest request) {
        return leaveRuleService.create(request);
    }

    @PutMapping("/{id}")
    public LeaveRule update(@PathVariable Long id, @Valid @RequestBody LeaveRuleRequest request) {
        return leaveRuleService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        leaveRuleService.delete(id);
    }
}
