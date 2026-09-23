package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.storage.FileStorageService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The Ministry's review of institution requests.
 *
 * SUPER_ADMIN-gated by SecurityConfig (/api/admin/**).
 */
@RestController
@RequestMapping("/api/admin/institution-requests")
public class AdminInstitutionRequestController {

    private final InstitutionRequestService service;
    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorage;

    public AdminInstitutionRequestController(InstitutionRequestService service,
                                             InstitutionRepository institutionRepository,
                                             UserRepository userRepository,
                                             FileStorageService fileStorage) {
        this.service = service;
        this.institutionRepository = institutionRepository;
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
    }

    public record RequestResponse(
            Long id,
            String proposedNameFr,
            String proposedNameAr,
            String contactName,
            String contactRole,
            String email,
            String phone,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime verifiedAt,
            OffsetDateTime decidedAt,
            String decidedByName,
            String decisionReason,
            Long institutionId,
            /**
             * ⚠️ EXISTING INSTITUTIONS THAT LOOK LIKE THIS ONE — flagged, not
             * blocked.
             *
             * The real risk of self-registration is impersonation: somebody
             * applying as a body already registered. Blocking on a name match
             * would refuse legitimate bodies with similar names; showing the
             * match puts it in front of the person who decides.
             */
            List<String> similarInstitutions
    ) {}

    public record ApproveBody(
            @NotBlank(message = "validation.required") String code,
            @NotBlank(message = "validation.required") String nameFr,
            @NotBlank(message = "validation.required") String nameAr
    ) {}

    public record RejectBody(
            @NotBlank(message = "validation.rejectionReasonRequired") String reason
    ) {}

    /* ══ reads ══ */

    @GetMapping
    @Transactional(readOnly = true)
    public List<RequestResponse> list(@RequestParam(required = false) InstitutionRequestStatus status) {
        List<InstitutionRequest> requests = service.list(status);
        Map<Long, String> deciders = decidersOf(requests);
        List<Institution> institutions = institutionRepository.findAll();
        return requests.stream().map(r -> toResponse(r, deciders, institutions)).toList();
    }

    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount() {
        return Map.of("pending", service.pendingCount());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public RequestResponse one(@PathVariable Long id) {
        InstitutionRequest r = service.find(id);
        return toResponse(r, decidersOf(List.of(r)), institutionRepository.findAll());
    }

    /**
     * The formal letter.
     *
     * ⚠️ THROUGH THE SERVER, WITH no-store — the same rule as photographs.
     * It is an official document naming a person and a body; it must not sit
     * in a proxy or a shared machine's cache after the administrator leaves.
     *
     * ⚠️ INLINE, NOT ATTACHMENT. The review screen shows it full-size beside
     * the form, because reading it IS the review.
     */
    @GetMapping("/{id}/letter")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> letter(@PathVariable Long id) throws IOException {
        InstitutionRequest r = service.find(id);
        Path path = fileStorage.resolve(r.getLetterPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String type = Files.probeContentType(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type != null ? type : "application/pdf"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new UrlResource(path.toUri()));
    }

    /* ══ the decision ══ */

    @PostMapping("/{id}/approve")
    public RequestResponse approve(@PathVariable Long id,
                                   @Valid @RequestBody ApproveBody body,
                                   Principal principal) {
        InstitutionRequest r = service.approve(id,
                new InstitutionRequestService.ApproveRequest(body.code(), body.nameFr(), body.nameAr()),
                actorId(principal));
        return one(r.getId());
    }

    @PostMapping("/{id}/reject")
    public RequestResponse reject(@PathVariable Long id,
                                  @Valid @RequestBody RejectBody body,
                                  Principal principal) {
        InstitutionRequest r = service.reject(id, body.reason(), actorId(principal));
        return one(r.getId());
    }

    /* ══ internals ══ */

    private RequestResponse toResponse(InstitutionRequest r, Map<Long, String> deciders,
                                       List<Institution> institutions) {
        return new RequestResponse(
                r.getId(), r.getProposedNameFr(), r.getProposedNameAr(),
                r.getContactName(), r.getContactRole(), r.getEmail(), r.getPhone(),
                r.getStatus().name(), r.getCreatedAt(), r.getVerifiedAt(), r.getDecidedAt(),
                r.getDecidedBy() == null ? null : deciders.get(r.getDecidedBy()),
                r.getDecisionReason(), r.getInstitutionId(),
                similar(r, institutions));
    }

    /**
     * A deliberately loose match: either name contains the other, in either
     * language. False positives cost a glance; a missed impersonation costs a
     * credential issued to the wrong body.
     */
    private static List<String> similar(InstitutionRequest r, List<Institution> institutions) {
        String fr = norm(r.getProposedNameFr());
        String ar = norm(r.getProposedNameAr());
        return institutions.stream()
                .filter(i -> overlaps(fr, norm(i.getNameFr())) || overlaps(ar, norm(i.getNameAr())))
                .map(Institution::getNameFr)
                .toList();
    }

    private static boolean overlaps(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || b.contains(a));
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private Map<Long, String> decidersOf(List<InstitutionRequest> requests) {
        List<Long> ids = requests.stream().map(InstitutionRequest::getDecidedBy)
                .filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of()
                : userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(User::getId, User::getFullName));
    }

    private Long actorId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}
