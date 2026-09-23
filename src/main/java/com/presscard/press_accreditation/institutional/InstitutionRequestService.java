package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.email.EmailTokenService;
import com.presscard.press_accreditation.error.DuplicateEmailException;
import com.presscard.press_accreditation.error.InstitutionalCardException;
import com.presscard.press_accreditation.storage.FileStorageService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Institutions apply; the Ministry decides.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE APPROVAL IS THE ONLY GATE.
 *
 * A request is a declaration: a name, a contact, an address and a formal
 * letter. None of it is trusted. The address is proven by a link; the letter
 * is read by a person. Only then does approve() create an institution and an
 * account — together, in one transaction, so there is never an institution
 * nobody can sign into or an account attached to nothing.
 *
 * ⚠️ THE MINISTRY FIXES THE OFFICIAL NAMES, not the applicant.
 *
 * The request PROPOSES a French and an Arabic name. The register takes what
 * the administrator confirms at approval, because it is the Ministry's
 * register, and a body's own spelling of itself is a proposal.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class InstitutionRequestService {

    private static final Logger log = LoggerFactory.getLogger("INSTITUTION_REQUEST_AUDIT");

    /**
     * ⚠️ SEVEN DAYS, not the twenty-four hours of a candidate's link.
     *
     * A candidate confirms the address they are sitting in front of. An
     * institution's request may be filed by an assistant and confirmed by
     * whoever reads the shared mailbox — days later, after a weekend.
     */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofDays(7);

    private final InstitutionRequestRepository repository;
    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorage;
    private final EmailService emailService;

    public InstitutionRequestService(InstitutionRequestRepository repository,
                                     InstitutionRepository institutionRepository,
                                     UserRepository userRepository,
                                     PasswordEncoder passwordEncoder,
                                     FileStorageService fileStorage,
                                     EmailService emailService) {
        this.repository = repository;
        this.institutionRepository = institutionRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.fileStorage = fileStorage;
        this.emailService = emailService;
    }

    public record SubmitRequest(
            String proposedNameFr,
            String proposedNameAr,
            String contactName,
            String contactRole,
            String email,
            String phone,
            String password,
            String locale
    ) {}

    public record ApproveRequest(String code, String nameFr, String nameAr) {}

    /* ══ the applicant's side ═════════════════════════════════ */

    @Transactional
    public InstitutionRequest submit(SubmitRequest req, MultipartFile letter) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);

        // ⚠️ The same 409 a candidate gets. An address already attached to an
        // account — candidate, reviewer, anything — cannot become an
        // institution's; the screen places the error beside the field.
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        if (repository.hasOpenRequest(email)) {
            throw new InstitutionalCardException("validation.institutionRequestPending");
        }
        if (letter == null || letter.isEmpty()) {
            throw new InstitutionalCardException("validation.institutionLetterRequired");
        }

        String rawToken = EmailTokenService.randomToken();

        InstitutionRequest request = repository.save(InstitutionRequest.builder()
                .proposedNameFr(req.proposedNameFr().trim())
                .proposedNameAr(req.proposedNameAr().trim())
                .contactName(req.contactName().trim())
                .contactRole(req.contactRole().trim())
                .email(email)
                .phone(req.phone() == null ? null : req.phone().replaceAll("\\s", ""))
                .locale(req.locale() == null || req.locale().isBlank() ? "fr" : req.locale())
                .passwordHash(passwordEncoder.encode(req.password()))
                // placeholder until the id exists — the letter's folder is keyed on it
                .letterPath("pending")
                .verificationTokenHash(EmailTokenService.hash(rawToken))
                .verificationExpiresAt(OffsetDateTime.now().plus(CONFIRMATION_WINDOW))
                .updatedAt(OffsetDateTime.now())
                .build());

        /*
         * ⚠️ STORED AFTER THE SAVE, INSIDE THE SAME TRANSACTION.
         *
         * The folder is keyed on the request's id, which only exists now. If
         * storing fails, the exception rolls the row back — there is never a
         * request without its letter, which is the one thing it must carry.
         */
        request.setLetterPath(fileStorage.storeInstitutionLetter(letter, request.getId()));
        repository.save(request);

        emailService.sendInstitutionRequestConfirm(
                request.getEmail(), request.getContactName(),
                request.getProposedNameFr(), rawToken, request.getLocale());

        log.info("INSTITUTION_REQUEST_SUBMITTED id={} name={} email={}",
                request.getId(), request.getProposedNameFr(), email);
        return request;
    }

    /**
     * Confirm the address.
     *
     * ⚠️ IDEMPOTENT, and NOTHING THROWS on a replay.
     *
     * A link clicked twice must not report failure for something that already
     * happened — the lesson from the candidate verification, where a caught
     * exception poisoned the transaction and a second click produced a 500.
     * The question is asked first, and the answer returned.
     *
     * @return true when this click confirmed it, false when it already was
     */
    @Transactional
    public boolean confirm(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InstitutionalCardException("validation.linkInvalid");
        }

        InstitutionRequest request = repository
                .findByVerificationTokenHash(EmailTokenService.hash(rawToken))
                .orElseThrow(() -> new InstitutionalCardException("validation.linkInvalid"));

        if (request.getVerifiedAt() != null) {
            return false;
        }
        if (request.getStatus() != InstitutionRequestStatus.SUBMITTED
                || request.getVerificationExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InstitutionalCardException("validation.linkInvalid");
        }

        request.setVerifiedAt(OffsetDateTime.now());
        request.setStatus(InstitutionRequestStatus.PENDING_REVIEW);
        request.setUpdatedAt(OffsetDateTime.now());
        repository.save(request);

        log.info("INSTITUTION_REQUEST_CONFIRMED id={}", request.getId());
        return true;
    }

    /* ══ the Ministry's side ══════════════════════════════════ */

    /**
     * Approve: create the institution and its account, from this request.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ ONE TRANSACTION, THREE WRITES.
     *
     * The institution, the INSTITUTION user pointing at it, and the request
     * marked APPROVED with both recorded. Any failure — a code already taken,
     * an address claimed since submission — rolls back all three. There is
     * never an institution without its account.
     *
     * ⚠️ THE ADDRESS IS RE-CHECKED HERE. Days pass between submission and
     * approval; somebody may have registered as a candidate with the same
     * address meanwhile. The unique index would catch it too, but with a
     * message nobody could act on.
     * ───────────────────────────────────────────────────────────────────
     */
    @Transactional
    public InstitutionRequest approve(Long id, ApproveRequest decision, Long actorId) {
        InstitutionRequest request = find(id);

        if (request.getStatus() != InstitutionRequestStatus.PENDING_REVIEW) {
            throw new InstitutionalCardException("validation.institutionRequestNotPending");
        }

        String code = decision.code() == null ? "" : decision.code().trim().toUpperCase(Locale.ROOT);
        if (code.isBlank() || isBlank(decision.nameFr()) || isBlank(decision.nameAr())) {
            throw new InstitutionalCardException("validation.institutionNamesRequired");
        }
        if (institutionRepository.findByCode(code).isPresent()) {
            throw new InstitutionalCardException("validation.institutionCodeTaken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException(request.getEmail());
        }

        Institution institution = institutionRepository.save(Institution.builder()
                .code(code)
                .nameFr(decision.nameFr().trim())
                .nameAr(decision.nameAr().trim())
                .active(true)
                .build());

        User account = userRepository.save(User.builder()
                .email(request.getEmail())
                .passwordHash(request.getPasswordHash())
                .role(UserRole.INSTITUTION)
                .institutionId(institution.getId())
                // ⚠️ The account belongs to the body, and is named after it.
                // The contact person is recorded on the request; staff turnover
                // must not rename the account.
                .fullName(institution.getNameFr())
                .phone(request.getPhone())
                // Proven by the confirmation link, before the Ministry read it.
                .emailVerified(true)
                .emailVerifiedAt(request.getVerifiedAt())
                .preferredLocale(request.getLocale())
                .build());

        request.setStatus(InstitutionRequestStatus.APPROVED);
        request.setInstitutionId(institution.getId());
        request.setDecidedBy(actorId);
        request.setDecidedAt(OffsetDateTime.now());
        // ⚠️ The hash has done its job — it lives on the account now.
        request.setPasswordHash(null);
        request.setUpdatedAt(OffsetDateTime.now());
        repository.save(request);

        emailService.sendInstitutionRequestApproved(
                account.getEmail(), request.getContactName(),
                institution.getNameFr(), request.getLocale());

        log.info("INSTITUTION_REQUEST_APPROVED id={} institution={} account={} by={}",
                id, code, account.getId(), actorId);
        return request;
    }

    /**
     * Reject, with a reason the applicant will read.
     *
     * ⚠️ THE PASSWORD HASH IS DISCARDED; THE REQUEST IS KEPT. Nothing needs the
     * hash any more, and a stored credential for an account that will never
     * exist is a liability. The request itself stays: who asked, and why they
     * were refused, is part of the record.
     */
    @Transactional
    public InstitutionRequest reject(Long id, String reason, Long actorId) {
        InstitutionRequest request = find(id);

        if (request.getStatus() != InstitutionRequestStatus.PENDING_REVIEW) {
            throw new InstitutionalCardException("validation.institutionRequestNotPending");
        }
        if (isBlank(reason)) {
            throw new InstitutionalCardException("validation.rejectionReasonRequired");
        }

        request.setStatus(InstitutionRequestStatus.REJECTED);
        request.setDecisionReason(reason.trim());
        request.setDecidedBy(actorId);
        request.setDecidedAt(OffsetDateTime.now());
        request.setPasswordHash(null);
        request.setUpdatedAt(OffsetDateTime.now());
        repository.save(request);

        emailService.sendInstitutionRequestRejected(
                request.getEmail(), request.getContactName(),
                request.getProposedNameFr(), request.getDecisionReason(),
                request.getLocale());

        log.info("INSTITUTION_REQUEST_REJECTED id={} by={}", id, actorId);
        return request;
    }

    /* ══ reads ════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<InstitutionRequest> list(InstitutionRequestStatus status) {
        return status == null
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public InstitutionRequest find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new InstitutionalCardException("validation.notFound"));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return repository.countByStatus(InstitutionRequestStatus.PENDING_REVIEW);
    }

    /* ══ housekeeping ═════════════════════════════════════════ */

    /**
     * Expire requests whose address was never confirmed.
     *
     * ⚠️ THE HASH IS CLEARED, THE ROW IS KEPT. An unconfirmed request may have
     * been filed with somebody else's address; nothing about it is trusted,
     * and a password hash for it has no reason to exist. The row stays so a
     * repeated attempt from the same address is visible.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void expireUnconfirmed() {
        List<InstitutionRequest> stale = repository.findUnconfirmedBefore(OffsetDateTime.now());
        for (InstitutionRequest r : stale) {
            r.setStatus(InstitutionRequestStatus.EXPIRED);
            r.setPasswordHash(null);
            r.setUpdatedAt(OffsetDateTime.now());
        }
        repository.saveAll(stale);
        if (!stale.isEmpty()) {
            log.info("INSTITUTION_REQUESTS_EXPIRED count={}", stale.size());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
