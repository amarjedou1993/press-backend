package com.presscard.press_accreditation.session;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * What a session produced.
 *
 * READABLE BY THE COMMISSION AS WELL AS THE AUTHORITY. A member who spent
 * three weeks examining dossiers has a legitimate interest in how the session
 * ended, and withholding the outcome of their own work is the kind of thing
 * that makes a system feel adversarial. Read-only for both — nothing here
 * changes a dossier.
 *
 * THE EXPORT IS ADMIN-ONLY: it is the file HAPA reports with, and it leaves
 * the building.
 */
@RestController
@RequestMapping("/api/admin/sessions/{sessionId}")
public class SessionResultsController {

    private final SessionResultsService resultsService;
    private final SessionResultsExporter exporter;

    public SessionResultsController(SessionResultsService resultsService,
                                    SessionResultsExporter exporter) {
        this.resultsService = resultsService;
        this.exporter = exporter;
    }

    @GetMapping("/results")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','REVIEWER')")
    public SessionResultsService.SessionResults results(@PathVariable Long sessionId) {
        return resultsService.results(sessionId);
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','REVIEWER')")
    public List<SessionResultsService.CandidateOutcome> candidates(
            @PathVariable Long sessionId) {
        return resultsService.candidates(sessionId);
    }

    /** The spreadsheet HAPA reports with. */
    @GetMapping("/export")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Resource> export(@PathVariable Long sessionId) {
        byte[] workbook = exporter.export(sessionId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"session-%d-resultats.xlsx\""
                                .formatted(sessionId))
                .body(new ByteArrayResource(workbook));
    }
}
