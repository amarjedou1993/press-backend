package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.application.ApplicationDtos.*;
import com.presscard.press_accreditation.document.ApplicationDocument;
import com.presscard.press_accreditation.document.DocumentType;
import com.presscard.press_accreditation.storage.FileStorageService;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;

/**
 * The candidate's application API.
 *
 * @PreAuthorize("hasRole('CANDIDATE')") is the coarse gate; OWNERSHIP is
 * enforced inside ApplicationService on every call, because "is a candidate"
 * and "is THIS candidate" are different questions and only the second one
 * protects another journalist's file.
 */
@RestController
@RequestMapping("/api/applications")
@PreAuthorize("hasRole('CANDIDATE')")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final FileStorageService fileStorage;
    private final UserRepository userRepository;

    public ApplicationController(ApplicationService applicationService,
                                 FileStorageService fileStorage,
                                 UserRepository userRepository) {
        this.applicationService = applicationService;
        this.fileStorage = fileStorage;
        this.userRepository = userRepository;
    }

    /* ══ reading ══ */

    @GetMapping
    public List<ApplicationResponse> myApplications(Principal principal) {
        return applicationService.listForCandidate(candidateId(principal)).stream()
                .map(ApplicationResponse::of).toList();
    }

    /** Everything the wizard needs in one round trip. */
    @GetMapping("/{id}")
    public ApplicationDetailResponse detail(@PathVariable Long id, Principal principal) {
        Long candidateId = candidateId(principal);
        Application application = applicationService.getOwned(id, candidateId);

        return new ApplicationDetailResponse(
                ApplicationResponse.of(application),
                applicationService.documents(id, candidateId).stream()
                        .map(DocumentResponse::of).toList(),
                applicationService.timeline(id, candidateId).stream()
                        .map(TimelineEntry::of).toList(),
                ReadinessResponse.of(applicationService.checkReadiness(id, candidateId)));
    }

    /** The checklist alone — polled by the wizard after each upload. */
    @GetMapping("/{id}/readiness")
    public ReadinessResponse readiness(@PathVariable Long id, Principal principal) {
        return ReadinessResponse.of(
                applicationService.checkReadiness(id, candidateId(principal)));
    }

    /* ══ drafting ══ */

    @PostMapping
    public ResponseEntity<ApplicationResponse> start(@Valid @RequestBody StartApplicationRequest request,
                                                     Principal principal) {
        Application application = applicationService.startOrResume(
                candidateId(principal), request.sessionId(), request.categoryId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApplicationResponse.of(application));
    }

    /* ══ evidence ══ */

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadFile(@PathVariable Long id,
                                                       @RequestParam DocumentType docType,
                                                       @RequestParam MultipartFile file,
                                                       Principal principal) {
        ApplicationDocument document = applicationService.attachFile(
                id, candidateId(principal), docType, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.of(document));
    }

    @PostMapping("/{id}/links")
    public ResponseEntity<DocumentResponse> addLink(@PathVariable Long id,
                                                    @Valid @RequestBody AttachLinkRequest request,
                                                    Principal principal) {
        ApplicationDocument document = applicationService.attachLink(
                id, candidateId(principal), request.docType(), request.url());
        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentResponse.of(document));
    }

    @DeleteMapping("/{id}/documents/{documentId}")
    public ResponseEntity<Void> removeDocument(@PathVariable Long id,
                                               @PathVariable Long documentId,
                                               Principal principal) {
        applicationService.removeDocument(id, candidateId(principal), documentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Stream back a stored file. The path NEVER appears in any API response;
     * a document is fetched by its id, and ownership is checked first — so a
     * stored path cannot be guessed or replayed against someone else's file.
     */
    @GetMapping("/{id}/documents/{documentId}/file")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id,
                                                 @PathVariable Long documentId,
                                                 Principal principal) throws IOException {
        Long candidateId = candidateId(principal);
        ApplicationDocument document = applicationService.documents(id, candidateId).stream()
                .filter(d -> d.getId().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new com.presscard.press_accreditation.error
                        .DocumentNotFoundException(documentId));

        if (document.getFilePath() == null) {
            throw new com.presscard.press_accreditation.error
                    .DocumentNotFoundException(documentId);
        }

        Path path = fileStorage.resolve(document.getFilePath());
        Resource resource = new UrlResource(path.toUri());
        String contentType = Files.probeContentType(path);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        contentType != null ? contentType : "application/octet-stream"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"%s\"".formatted(path.getFileName()))
                .body(resource);
    }

    /* ══ submission ══ */

    @PostMapping("/{id}/submit")
    public ApplicationResponse submit(@PathVariable Long id, Principal principal) {
        return ApplicationResponse.of(
                applicationService.submit(id, candidateId(principal)));
    }

    /* ══ helper ══ */

    private Long candidateId(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow()
                .getId();
    }
}
