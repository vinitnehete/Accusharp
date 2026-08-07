package com.accusharp.hrms.controller;

import com.accusharp.hrms.entity.AuditLog;
import com.accusharp.hrms.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Company-scoped ADMIN and platform accounts only - see SECURITY.md. Not HR: the trail includes HR's own actions. */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("@authz.can('AUDIT_READ')")
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    public List<AuditLog> getRecent(@RequestParam(defaultValue = "50") int limit) {
        return auditService.recent(limit);
    }
}
