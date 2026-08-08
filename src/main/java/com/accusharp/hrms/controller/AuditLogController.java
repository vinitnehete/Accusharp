package com.accusharp.hrms.controller;

import com.accusharp.hrms.entity.AuditLog;
import com.accusharp.hrms.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Company-scoped ADMIN and platform accounts only for reads (not HR - the
 * trail includes HR's own actions); purging is platform-only - see
 * SECURITY.md and {@code PermissionCode.AUDIT_MANAGE}'s Javadoc.
 */
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    @PreAuthorize("@authz.can('AUDIT_READ')")
    @GetMapping
    public List<AuditLog> getRecent(@RequestParam(defaultValue = "50") int limit) {
        return auditService.recent(limit);
    }

    /** Unbounded CSV export of a date range - for retention/archival, where {@link #getRecent}'s 200-row cap would silently drop history. */
    @PreAuthorize("@authz.can('AUDIT_READ')")
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        String csv = auditService.renderCsv(
                fromDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        String filename = "audit-log-%s-to-%s.csv".formatted(fromDate, toDate);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    /**
     * Deletes every row older than {@code beforeDate} - platform-only
     * (AUDIT_MANAGE, never granted to a company role). There is no
     * automatic retention policy; an operator runs this deliberately.
     */
    @PreAuthorize("@authz.can('AUDIT_MANAGE')")
    @DeleteMapping
    public Map<String, Long> purge(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate beforeDate) {
        Instant cutoff = beforeDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        return Map.of("deleted", auditService.purgeOlderThan(cutoff));
    }
}
