package com.accusharp.hrms.controller;

import com.accusharp.hrms.dto.BulkImportResult;
import com.accusharp.hrms.dto.LeaveDecisionRequest;
import com.accusharp.hrms.dto.LeaveHrDirectRequest;
import com.accusharp.hrms.dto.LeaveRequestPayload;
import com.accusharp.hrms.dto.LeaveResponse;
import com.accusharp.hrms.enums.LeaveStatus;
import com.accusharp.hrms.security.UserPrincipal;
import com.accusharp.hrms.service.leave.LeaveService;
import com.accusharp.hrms.util.LeaveCsvParser;
import com.accusharp.hrms.util.ParsedCsvRow;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code approverId} on every decision endpoint is overwritten with the
 * caller's own authenticated username below - {@code LeaveService} uses it
 * to verify the approver actually supervises the requester (or holds
 * HR/ADMIN), so it must be who really called the API. {@code reject} and
 * {@code cancel} now require {@code LEAVE_APPROVE} same as {@code approve} -
 * previously neither had any role check at all, unlike {@code approve}.
 */
@RestController
@RequestMapping("/api/leaves")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveService leaveService;

    @PreAuthorize("@authz.can('LEAVE_APPLY')")
    @PostMapping
    public ResponseEntity<LeaveResponse> apply(@Valid @RequestBody LeaveRequestPayload payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leaveService.apply(payload));
    }

    /** HR/ADMIN entering an already-approved leave directly - skips apply/endorse. Gated by LEAVE_APPROVE, same trust bar as final approval. */
    @PreAuthorize("@authz.can('LEAVE_APPROVE')")
    @PostMapping("/hr-create")
    public ResponseEntity<LeaveResponse> hrDirectCreate(@AuthenticationPrincipal UserPrincipal principal,
                                                        @Valid @RequestBody LeaveHrDirectRequest request) {
        request.setApproverId(principal.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(leaveService.hrDirectCreate(request));
    }

    /**
     * CSV variant of {@link #hrDirectCreate} for backfilling a batch of
     * already-approved leaves at once - see {@link LeaveCsvParser} for the
     * expected header. A row that fails to parse (bad date, unknown leave
     * type) is reported the same way a row that fails to apply (insufficient
     * balance, overlapping leave) is: as an entry in {@code errors}, never as
     * a thrown exception that discards the rest of the file.
     */
    @PreAuthorize("@authz.can('LEAVE_APPROVE')")
    @PostMapping(value = "/bulk-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkImportResult<LeaveResponse> hrDirectCreateBulkCsv(@AuthenticationPrincipal UserPrincipal principal,
                                                                  @RequestParam("file") MultipartFile file) {
        List<ParsedCsvRow<LeaveHrDirectRequest>> rows = LeaveCsvParser.parse(file);
        List<LeaveResponse> succeeded = new ArrayList<>();
        List<BulkImportResult.RowError> errors = new ArrayList<>();

        for (ParsedCsvRow<LeaveHrDirectRequest> row : rows) {
            if (!row.isOk()) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), null, row.error()));
                continue;
            }
            LeaveHrDirectRequest entry = row.value();
            entry.setApproverId(principal.getUsername());
            try {
                succeeded.add(leaveService.hrDirectCreate(entry));
            } catch (RuntimeException e) {
                errors.add(new BulkImportResult.RowError(row.rowNumber(), entry.getUserId(), e.getMessage()));
            }
        }
        return BulkImportResult.of(rows.size(), succeeded, errors);
    }

    @PreAuthorize("@authz.can('LEAVE_SUPERVISOR_APPROVE')")
    @PostMapping("/{id}/supervisor-approve")
    public LeaveResponse supervisorApprove(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable Long id,
                                           @Valid @RequestBody LeaveDecisionRequest decision) {
        decision.setApproverId(principal.getUsername());
        return leaveService.supervisorApprove(id, decision);
    }

    @PreAuthorize("@authz.can('LEAVE_APPROVE')")
    @PostMapping("/{id}/approve")
    public LeaveResponse approve(@AuthenticationPrincipal UserPrincipal principal,
                                 @PathVariable Long id, @Valid @RequestBody LeaveDecisionRequest decision) {
        decision.setApproverId(principal.getUsername());
        return leaveService.approve(id, decision);
    }

    @PreAuthorize("@authz.can('LEAVE_APPROVE')")
    @PostMapping("/{id}/reject")
    public LeaveResponse reject(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable Long id, @Valid @RequestBody LeaveDecisionRequest decision) {
        decision.setApproverId(principal.getUsername());
        return leaveService.reject(id, decision);
    }

    @PreAuthorize("@authz.can('LEAVE_APPROVE')")
    @PostMapping("/{id}/cancel")
    public LeaveResponse cancel(@AuthenticationPrincipal UserPrincipal principal,
                                @PathVariable Long id, @Valid @RequestBody LeaveDecisionRequest decision) {
        decision.setApproverId(principal.getUsername());
        return leaveService.cancel(id, decision);
    }

    @PreAuthorize("@authz.can('LEAVE_READ')")
    @GetMapping("/{id}")
    public LeaveResponse getById(@PathVariable Long id) {
        return leaveService.getById(id);
    }

    @PreAuthorize("@authz.can('LEAVE_READ')")
    @GetMapping("/employee/{userId}")
    public List<LeaveResponse> getHistory(@PathVariable String userId) {
        return leaveService.getHistory(userId);
    }

    @PreAuthorize("@authz.can('LEAVE_READ')")
    @GetMapping("/pending/{supervisorUserId}")
    public List<LeaveResponse> getPendingForSupervisor(@PathVariable String supervisorUserId) {
        return leaveService.getPendingFor(supervisorUserId);
    }

    @PreAuthorize("@authz.can('LEAVE_READ')")
    @GetMapping
    public List<LeaveResponse> getByStatus(@RequestParam LeaveStatus status) {
        return leaveService.getByStatus(status);
    }

    @PreAuthorize("@authz.can('LEAVE_READ')")
    @GetMapping("/calendar")
    public List<LeaveResponse> getCalendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return leaveService.getCalendar(fromDate, toDate);
    }
}
