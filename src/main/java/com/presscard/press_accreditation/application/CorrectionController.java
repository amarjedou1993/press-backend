package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.document.ApplicationDocument;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;

/**
 * The candidate's correction screen.
 *
 * Separate from ApplicationController because the rules genuinely differ: a
 * draft may be edited freely, a correction may touch ONLY what the commission
 * flagged. Mixing them would mean every method re-deciding which regime it is
 * under.
 */
@RestController
@RequestMapping("/api/applications/{id}/correction")
@PreAuthorize("hasRole('CANDIDATE')")
public class CorrectionController {

    public record ReplaceLinkRequest(@NotBlank String url) {}

    private final CorrectionService correctionService;
    private final UserRepository userRepository;

    public CorrectionController(CorrectionService correctionService,
                                UserRepository userRepository) {
        this.correctionService = correctionService;
        this.userRepository = userRepository;
    }

    /** What remains to be corrected, and whether resubmission is possible. */
    @GetMapping
    public CorrectionService.CorrectionState state(@PathVariable Long id,
                                                   Principal principal) {
        return correctionService.state(id, candidateId(principal));
    }

    /** Replace one flagged file. */
    @PostMapping(value = "/documents/{documentId}",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApplicationDocument replaceDocument(@PathVariable Long id,
                                               @PathVariable Long documentId,
                                               @RequestParam MultipartFile file,
                                               Principal principal) {
        return correctionService.replaceDocument(id, documentId, file, candidateId(principal));
    }

    /** Replace one flagged link. */
    @PutMapping("/documents/{documentId}/link")
    public ApplicationDocument replaceLink(@PathVariable Long id,
                                           @PathVariable Long documentId,
                                           @Valid @RequestBody ReplaceLinkRequest request,
                                           Principal principal) {
        return correctionService.replaceLink(id, documentId, request.url(),
                candidateId(principal));
    }

    /**
     * Return the corrected dossier to the commission.
     *
     * Answers with the CORRECTION STATE rather than an application DTO: the
     * screen consumes that object on every other call, so it gets its new
     * state in the same round trip — inCorrection false, nothing outstanding
     * — instead of a second shape it would have to reconcile.
     */
    @PostMapping("/resubmit")
    public CorrectionService.CorrectionState resubmit(@PathVariable Long id,
                                                      Principal principal) {
        Long candidate = candidateId(principal);
        correctionService.resubmit(id, candidate);
        return correctionService.state(id, candidate);
    }

    private Long candidateId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}
