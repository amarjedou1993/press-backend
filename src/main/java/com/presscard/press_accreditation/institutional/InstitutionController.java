package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.error.InstitutionalCardException;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The institution's own space: its roll, and nothing else.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE INSTITUTION IS READ FROM THE ACCOUNT, NEVER FROM THE REQUEST.
 *
 * Every method below resolves institutionId from the signed-in user. Taking
 * it from a path variable or a body would let one body file staff for
 * another, or read another's roll — and the accounts belong to institutions
 * rather than to people, so a leaked one is a leaked organisation.
 *
 * There is no endpoint here that names an institution. That is the design,
 * not an omission.
 * ───────────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/api/institution")
@PreAuthorize("hasRole('INSTITUTION')")
public class InstitutionController {

    private final InstitutionalCardService service;
    private final InstitutionalImportCommitter importCommitter;
    private final InstitutionalImportTemplate template;
    private final InstitutionalCardRepository repository;
    private final InstitutionRepository institutionRepository;
    private final PhotoStorageService photoStorage;
    private final UserRepository userRepository;

    public InstitutionController(InstitutionalCardService service,
                                 InstitutionalImportCommitter importCommitter,
                                 InstitutionalImportTemplate template,
                                 InstitutionalCardRepository repository,
                                 InstitutionRepository institutionRepository,
                                 PhotoStorageService photoStorage,
                                 UserRepository userRepository) {
        this.service = service;
        this.importCommitter = importCommitter;
        this.template = template;
        this.repository = repository;
        this.institutionRepository = institutionRepository;
        this.photoStorage = photoStorage;
        this.userRepository = userRepository;
    }

    /* ══ responses ══ */

    /** One filing, as its institution sees it. */
    public record FilingResponse(
            Long id,
            String fullName,
            String identityNumber,
            LocalDate birthdate,
            String birthplace,
            String jobTitle,
            Long categoryId,
            Long specialisationId,
            boolean hasPhoto,
            /**
             * ⚠️ THE LINE BETWEEN A FILING AND A CARD.
             *
             * False means the Ministry has not yet granted: the institution
             * may still edit or withdraw it, and there is no card number to
             * show. True means a credential exists and nothing here may
             * change it.
             */
            boolean granted,
            /**
             * ⚠️ Whether this filing replaces a card the holder already
             * carries.
             *
             * The institution needs it because re-filing its roll produces
             * both kinds in one upload, and "which of these are new people"
             * is the question it will ask of the result.
             */
            boolean renewal,
            /** The number being replaced — null for a first filing. */
            String renewedFromCardNumber,
            String cardNumber,
            LocalDate expiresAt,
            String status,
            OffsetDateTime filedAt
    ) {
        static FilingResponse of(InstitutionalCard c, String renewedFromCardNumber) {
            return new FilingResponse(
                    c.getId(), c.getFullName(), c.getIdentityNumber(),
                    c.getBirthdate(), c.getBirthplace(), c.getJobTitle(),
                    c.getCategoryId(), c.getSpecialisationId(),
                    c.getPhotoPath() != null,
                    c.isGranted(),
                    c.isRenewal(),
                    renewedFromCardNumber,
                    c.getCardNumber(), c.getExpiresAt(),
                    c.getStatus().name(), c.getFiledAt());
        }
    }

    /** The institution's own identity, for its space's header. */
    public record InstitutionResponse(Long id, String code, String nameFr, String nameAr) {}

    /* ══ the roll ══ */

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public InstitutionResponse me(Principal principal) {
        Institution institution = institutionRepository.findById(institutionId(principal))
                .orElseThrow();
        return new InstitutionResponse(institution.getId(), institution.getCode(),
                institution.getNameFr(), institution.getNameAr());
    }

    @GetMapping("/staff")
    @Transactional(readOnly = true)
    public List<FilingResponse> staff(Principal principal) {
        List<InstitutionalCard> rows = repository
                .findByInstitutionIdOrderByFiledAtDesc(institutionId(principal));

        /*
         * ───────────────────────────────────────────────────────────────
         * ⚠️ ONE QUERY FOR THE PREDECESSORS, not one per renewal.
         *
         * Re-filing a roll produces renewals and first filings in one upload,
         * and each renewal names the card it replaces by id. Resolving that
         * id to a number inside the map() would be a lookup per row — a
         * hundred round trips on a roll of two hundred, on the one screen an
         * institution opens to do its whole job.
         *
         * The same correction already made to toResponses on the Ministry's
         * side, and to four controllers before it. Written this way from the
         * start here rather than found later by a slow page.
         * ───────────────────────────────────────────────────────────────
         */
        List<Long> predecessorIds = rows.stream()
                .map(InstitutionalCard::getRenewedFromCardId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, String> numbers = predecessorIds.isEmpty()
                ? Map.of()
                : repository.findAllById(predecessorIds).stream()
                /*
                 * ⚠️ A predecessor may carry no number: the chain is
                 * set at FILING, and the card it points at could still
                 * be an ungranted filing if a roll was uploaded twice
                 * before the Ministry acted. The row is still shown —
                 * the badge simply appears without a number.
                 */
                .filter(c -> c.getCardNumber() != null)
                .collect(Collectors.toMap(
                        InstitutionalCard::getId,
                        InstitutionalCard::getCardNumber));

        return rows.stream()
                .map(c -> FilingResponse.of(c,
                        c.getRenewedFromCardId() == null
                                ? null
                                : numbers.get(c.getRenewedFromCardId())))
                .toList();
    }

    /* ══ filing, one at a time ══ */

    public record FilingBody(
            @NotBlank(message = "validation.fullNameRequired") String fullName,
            @NotBlank(message = "validation.identityRequired") String identityNumber,
            LocalDate birthdate,
            String birthplace,
            String jobTitle,
            Long categoryId,
            Long specialisationId
    ) {}

    @PostMapping("/staff")
    public FilingResponse file(@Valid @RequestBody FilingBody body, Principal principal) {
        User account = account(principal);
        return FilingResponse.of(service.file(
                account.getInstitutionId(),
                new InstitutionalCardService.FilingRequest(
                        body.fullName(), body.identityNumber(), body.birthdate(),
                        body.birthplace(), body.jobTitle(),
                        body.categoryId(), body.specialisationId()),
                account.getId()), null);
    }

    @PutMapping("/staff/{id}")
    public FilingResponse update(@PathVariable Long id,
                                 @Valid @RequestBody FilingBody body,
                                 Principal principal) {
        User account = account(principal);
        return FilingResponse.of(service.updateFiling(
                id, account.getInstitutionId(),
                new InstitutionalCardService.FilingRequest(
                        body.fullName(), body.identityNumber(), body.birthdate(),
                        body.birthplace(), body.jobTitle(),
                        body.categoryId(), body.specialisationId()),
                account.getId()), null);
    }

    /**
     * Withdraw a filing.
     *
     * ⚠️ ONLY BEFORE THE GRANT — the service refuses afterwards. Once a card
     * exists it is the Ministry's to revoke; an institution cannot delete a
     * credential out of the register.
     */
    @DeleteMapping("/staff/{id}")
    public ResponseEntity<Void> withdraw(@PathVariable Long id, Principal principal) {
        User account = account(principal);
        service.withdrawFiling(id, account.getInstitutionId(), account.getId());
        return ResponseEntity.noContent().build();
    }

    /* ══ the photograph ══ */

    /**
     * Attach or replace a filing's photograph.
     *
     * ⚠️ ALLOWED AFTER THE GRANT, unlike every other edit here.
     *
     * The Ministry grants without waiting for photographs — the card then
     * sits out of the printer's queue until one arrives. Refusing this after
     * the grant would strand exactly those cards, and the institution is the
     * only party that has the pictures.
     *
     * ⚠️ It does not invalidate the signature: the canonical form covers the
     * number, the identity, the name and the dates. A face is printed, not
     * signed.
     */
    @PostMapping(value = "/staff/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public FilingResponse attachPhoto(@PathVariable Long id,
                                      @RequestParam("file") MultipartFile file,
                                      Principal principal) {
        User account = account(principal);
        InstitutionalCard card = repository.findById(id)
                .filter(c -> c.getInstitutionId().equals(account.getInstitutionId()))
                .orElseThrow(() -> new InstitutionalCardException("validation.notFound"));

        String path = photoStorage.storeForInstitutionalCard(
                file, card.getId(), card.getPhotoPath());

        card.setPhotoPath(path);
        card.setPhotoUploadedAt(OffsetDateTime.now());
        card.setUpdatedAt(OffsetDateTime.now());
        repository.save(card);

        return FilingResponse.of(card, null);
    }

    /* ══ the bulk import ══ */

    /**
     * The blank workbook.
     *
     * ⚠️ Without it the first import fails on column order, and nothing tells
     * the institution what the columns should be. A format nobody can produce
     * is a feature nobody can use.
     */
    @GetMapping(value = "/staff/import/template", produces =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> importTemplate() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"modele-agents-%s.xlsx\""
                                .formatted(LocalDate.now()))
                .body(template.build());
    }

    /**
     * File a whole staff roll from one archive.
     *
     * ⚠️ ONE CALL, unlike the honour import's preview-then-commit.
     *
     * That one grants cards and takes a B number per row, so it checks twice.
     * This one only files, and a filing is deletable — the result reports
     * what was created and what was refused, and the institution corrects
     * from there.
     */
    @PostMapping(value = "/staff/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InstitutionalImportCommitter.CommitResult importStaff(
            @RequestParam("file") MultipartFile file, Principal principal) {
        User account = account(principal);
        return importCommitter.commit(account.getInstitutionId(), file, account.getId());
    }

    /* ══ internals ══ */

    private User account(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow();
    }

    /**
     * ⚠️ NEVER null for an INSTITUTION account — a CHECK constraint on users
     * refuses the combination. Read here rather than defended against,
     * because a null would mean the database had already failed.
     */
    private Long institutionId(Principal principal) {
        return account(principal).getInstitutionId();
    }
}
