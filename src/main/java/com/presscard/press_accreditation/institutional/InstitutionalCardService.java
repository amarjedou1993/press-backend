package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.card.CardLifecycleService;
import com.presscard.press_accreditation.card.CardSigningService;
import com.presscard.press_accreditation.card.CardStatus;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.error.InstitutionalCardException;
import com.presscard.press_accreditation.error.InstitutionalCardNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Filing an institution's staff, and granting their cards.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ TWO ACTS, TWO ACTORS, AND THE SEPARATION IS THE POINT.
 *
 * The institution FILES: a name, an identity, a photograph. That row is a
 * declaration — "this person works here" — and the body making it is itself a
 * press authority, which is why no commission examines it.
 *
 * The Ministry GRANTS: the number, the signature, the expiry. A press card is
 * the Ministry's document even when nobody examined it, and a body issuing
 * credentials to its own staff would be issuing them to itself.
 *
 * ⚠️ AND THE NUMBER IS TAKEN AT THE GRANT, NOT AT THE FILING.
 *
 * A filing that is never granted, or is deleted, consumes no C number. The
 * same discipline as CardService: a sequence value spent on something that
 * did not happen leaves a permanent gap, and a register with unexplained
 * holes invites the question of what was removed.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class InstitutionalCardService {

    private static final Logger log = LoggerFactory.getLogger("INSTITUTIONAL_AUDIT");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * ⚠️ FIXED, NOT CONFIGURABLE — exactly as "B" is for honour cards.
     *
     * The letter is what tells a checkpoint which authority stands behind the
     * card: C means an institution vouched for its own employee. An
     * installation able to rename it could make the three series
     * indistinguishable, which is the one thing the letters exist to prevent.
     */
    private static final String SERIES = "C";

    private final InstitutionalCardRepository repository;
    private final InstitutionRepository institutionRepository;
    private final InstitutionalCardStatusHistoryRepository historyRepository;
    private final CardSigningService signingService;
    private final CardLifecycleService lifecycleService;
    private final AppProperties props;

    public InstitutionalCardService(InstitutionalCardRepository repository,
                                    InstitutionRepository institutionRepository,
                                    InstitutionalCardStatusHistoryRepository historyRepository,
                                    CardSigningService signingService,
                                    CardLifecycleService lifecycleService,
                                    AppProperties props) {
        this.repository = repository;
        this.institutionRepository = institutionRepository;
        this.historyRepository = historyRepository;
        this.signingService = signingService;
        this.lifecycleService = lifecycleService;
        this.props = props;
    }

    /* ══ filing — the institution's act ═══════════════════════ */

    /** What an institution supplies about one employee. */
    public record FilingRequest(
            String fullName,
            String identityNumber,
            LocalDate birthdate,
            String birthplace,
            String jobTitle,
            Long categoryId,
            Long specialisationId
    ) {}

    @Transactional
    public InstitutionalCard file(Long institutionId, FilingRequest req, Long filedBy) {
        Institution institution = institutionRepository.findById(institutionId)
                .filter(Institution::isActive)
                .orElseThrow(() -> new InstitutionalCardException(
                        "Institution inconnue ou désactivée."));

        String identity = req.identityNumber() == null ? "" : req.identityNumber().replaceAll("\\s", "");
        if (identity.isBlank()) {
            throw new InstitutionalCardException("validation.identityRequired");
        }
        if (req.fullName() == null || req.fullName().isBlank()) {
            throw new InstitutionalCardException("validation.fullNameRequired");
        }

        /*
         * ───────────────────────────────────────────────────────────────
         * ⚠️ A LIVE CARD IS NO LONGER A REFUSAL — IT IS A RENEWAL.
         *
         * Filing someone who already holds a card used to be rejected
         * outright. That was right when a C card could only be issued once;
         * it is wrong now, because RE-FILING THE ROLL IS HOW AN INSTITUTION
         * RENEWS.
         *
         * The existing card is CHAINED rather than refused. A body uploads
         * its current staff list each cycle; whoever is absent from it is
         * simply not renewed, and nobody has to remember to withdraw them.
         *
         * ⚠️ THE CHAIN IS SET HERE, AT THE FILING — not at the grant.
         *
         * That is the difference from a press card, where the predecessor is
         * only known at issuance because a commission stands between. Here
         * the institution is re-affirming an employment it already declared,
         * and says so at the moment it declares it again.
         *
         * ⚠️ WHAT IS STILL REFUSED is a SECOND renewal of the same card. The
         * database enforces one successor per card; reaching this check means
         * the roll was uploaded twice, and the message says so rather than
         * surfacing a constraint name.
         * ───────────────────────────────────────────────────────────────
         */
        InstitutionalCard previous = repository
                .findLiveCard(institutionId, identity).orElse(null);

        if (previous != null && repository.existsByRenewedFromCardId(previous.getId())) {
            throw new InstitutionalCardException("validation.holderAlreadyRenewed");
        }

        InstitutionalCard card = repository.save(InstitutionalCard.builder()
                .institutionId(institutionId)
                .fullName(req.fullName().trim())
                .identityNumber(identity)
                .birthdate(req.birthdate())
                .birthplace(req.birthplace())
                .jobTitle(req.jobTitle())
                .categoryId(req.categoryId())
                .specialisationId(req.specialisationId())
                .renewedFromCardId(previous == null ? null : previous.getId())
                .status(CardStatus.VALID)
                .filedBy(filedBy)
                .build());

        log.info("INSTITUTIONAL_FILED id={} institution={} identity={} renewalOf={} by={}",
                card.getId(), institution.getCode(), identity,
                previous == null ? "—" : previous.getCardNumber(), filedBy);
        return card;
    }
    /**
     * Correct a filing.
     *
     * ⚠️ REFUSED ONCE GRANTED. After the grant the details are signed and may
     * be printed — changing them would leave the card saying something the
     * signature does not cover, which is precisely what a verification checks.
     * The Ministry corrects a granted card through its own path.
     */
    @Transactional
    public InstitutionalCard updateFiling(Long id, Long institutionId,
                                          FilingRequest req, Long actorId) {
        InstitutionalCard card = ownedBy(id, institutionId);

        if (card.isGranted()) {
            throw new InstitutionalCardException("validation.grantedCannotBeEdited");
        }

        card.setFullName(req.fullName().trim());
        card.setIdentityNumber(req.identityNumber().replaceAll("\\s", ""));
        card.setBirthdate(req.birthdate());
        card.setBirthplace(req.birthplace());
        card.setJobTitle(req.jobTitle());
        card.setCategoryId(req.categoryId());
        card.setSpecialisationId(req.specialisationId());
        card.setUpdatedAt(OffsetDateTime.now());
        repository.save(card);

        log.info("INSTITUTIONAL_UPDATED id={} by={}", id, actorId);
        return card;
    }

    /**
     * Withdraw a filing before it is granted.
     *
     * ⚠️ A DELETE, NOT A STATUS. Nothing was issued, so there is nothing to
     * revoke and no history worth keeping: an institution that filed a name
     * in error should be able to remove it, and the C sequence is untouched
     * because no number was ever taken.
     */
    @Transactional
    public void withdrawFiling(Long id, Long institutionId, Long actorId) {
        InstitutionalCard card = ownedBy(id, institutionId);

        if (card.isGranted()) {
            throw new InstitutionalCardException("validation.grantedCannotBeWithdrawn");
        }
        repository.delete(card);
        log.info("INSTITUTIONAL_WITHDRAWN id={} institution={} by={}",
                id, institutionId, actorId);
    }

    /**
     * The cards a producer may make.
     *
     * ⚠️ THE BOUNDARY, NOT THE SCREEN. findProducible carries the photograph
     * clause — a card without a face reaches nobody's queue, because a
     * verification page showing no one verifies nothing.
     */
    @Transactional(readOnly = true)
    public List<InstitutionalCard> producible() {
        return repository.findProducible(CardStatus.VALID);
    }


    /* ══ granting — the Ministry's act ════════════════════════ */

    /**
     * Issue the card for a filing.
     *
     * ⚠️ IDEMPOTENT. A filing already granted gets its existing card back
     * rather than a second number — the same property CardService.issue has,
     * and for the same reason: a double click must not produce two documents.
     */
    @Transactional
    public InstitutionalCard grant(Long id, Long grantedBy, LocalDate expiresAt) {
        InstitutionalCard card = repository.findById(id)
                .orElseThrow(() -> new InstitutionalCardNotFoundException(id));

        if (card.isGranted()) {
            return card;
        }

        /* ── every precondition BEFORE a number is taken ── */

        if (expiresAt == null || !expiresAt.isAfter(LocalDate.now())) {
            throw new InstitutionalCardException("validation.expiryMustBeFuture");
        }
        if (card.getIdentityNumber() == null || card.getIdentityNumber().isBlank()) {
            throw new InstitutionalCardException("validation.identityRequired");
        }

        /*
         * ⚠️ holderHasLiveCard IS GONE FROM HERE, AND IT HAD TO BE.
         *
         * It refused a grant when the holder already carried a card — which
         * is the definition of a renewal. Left in place it would have refused
         * every single one, with a message saying the person is already
         * carded: true, and precisely the reason we are here.
         *
         * What it protected against — two live cards for one person at one
         * body — is still enforced, by uq_institutional_live_holder and by
         * the retirement below. The database holds the rule; this check was
         * only ever its politer twin.
         */

        /*
         * ⚠️ THE PHOTOGRAPH IS NOT CHECKED HERE, AND THAT IS DELIBERATE.
         *
         * A card may be granted without one: the institution attaches it
         * afterwards, and findProducible keeps the card out of the printer's
         * queue until it does. Refusing the grant would mean the Ministry
         * waiting on the institution for something it can supply later —
         * exactly the arrangement the honour card import settled on.
         */

        LocalDate issuedAt = LocalDate.now();
        String cardNumber = nextCardNumber(issuedAt);

        /*
         * ═══════════════════════════════════════════════════════════════
         * ⚠️ THE PREDECESSOR IS RETIRED FIRST, AND THE ORDER IS LOAD-BEARING.
         *
         * uq_institutional_live_holder allows ONE granted, non-revoked card
         * per person per body. A renewal means the same person holding two —
         * so if this card's grant fields were written before the old card was
         * revoked, both would satisfy the index's predicate for an instant,
         * and the write would be refused.
         *
         * Not with a message anyone could act on: "duplicate key value
         * violates unique constraint", on the one operation this feature
         * exists to perform.
         *
         * Retiring first means the two never coexist. The constraint stays
         * exactly as strict, the database still enforces it, and no index had
         * to be widened to accommodate a sequence.
         *
         * ⚠️ IF THIS BLOCK MOVES BELOW THE SETTERS, RENEWALS STOP WORKING.
         *
         * And the number is passed because the retired card's reason names
         * its successor: "Remplacée par la carte n° C - 0043 / 28" is what
         * makes the register readable years later.
         * ═══════════════════════════════════════════════════════════════
         */
        if (card.isRenewal()) {
            lifecycleService.retireInstitutionalOnRenewal(
                    card.getRenewedFromCardId(), grantedBy, cardNumber);
        }

        card.setCardNumber(cardNumber);
        card.setIssuedAt(issuedAt);
        card.setExpiresAt(expiresAt);
        card.setVerificationToken(newVerificationToken());
        card.setGrantedBy(grantedBy);
        card.setGrantedAt(OffsetDateTime.now());

        /*
         * ⚠️ THE SAME CANONICAL FORM AND THE SAME KEY as every other series.
         *
         * A scan cannot tell that this card skipped the commission — and it
         * must not. That distinction belongs on the C in the number and in the
         * register, not in whether the credential verifies. A card the
         * Ministry granted must never read as forged.
         */
        String canonical = CardSigningService.canonicalForm(
                cardNumber,
                card.getIdentityNumber(),
                card.getFullName(),
                issuedAt.toString(),
                expiresAt.toString());

        card.setSignature(signingService.sign(canonical));
        card.setSignatureKeyId(signingService.currentKeyId());
        card.setUpdatedAt(OffsetDateTime.now());
        repository.save(card);

        historyRepository.save(InstitutionalCardStatusHistory.builder()
                .institutionalCardId(card.getId())
                .fromStatus(null)
                .toStatus(CardStatus.VALID)
                .reason(card.isRenewal() ? "Carte renouvelée." : "Carte octroyée.")
                .actorId(grantedBy)
                .build());

        log.info("INSTITUTIONAL_GRANTED number={} institution={} holder={} renewalOf={} by={} expires={}",
                cardNumber, card.getInstitutionId(), card.getIdentityNumber(),
                card.getRenewedFromCardId() == null ? "—" : card.getRenewedFromCardId(),
                grantedBy, expiresAt);
        return card;
    }
    /** One filing's outcome inside a batch. */
    public record GrantOutcome(
            Long id,
            String fullName,
            boolean granted,
            String cardNumber,
            String failureFr
    ) {}

    public record BatchResult(int requested, int granted, int failed,
                              List<GrantOutcome> outcomes) {}

    /**
     * Grant many.
     *
     * ⚠️ EACH INDEPENDENTLY, and failures are NAMED. One employee filed twice
     * must not cost the other thirty-nine their cards — the same rule as
     * CardService.issueMany, and the administrator reads WHO failed rather
     * than a count.
     */
    @Transactional
    public BatchResult grantMany(List<Long> ids, Long grantedBy, LocalDate expiresAt) {
        List<GrantOutcome> outcomes = new ArrayList<>();
        int granted = 0;
        int failed = 0;

        for (Long id : ids) {
            String name = repository.findById(id)
                    .map(InstitutionalCard::getFullName).orElse("—");
            try {
                InstitutionalCard card = grant(id, grantedBy, expiresAt);
                outcomes.add(new GrantOutcome(id, name, true, card.getCardNumber(), null));
                granted++;
            } catch (RuntimeException e) {
                outcomes.add(new GrantOutcome(id, name, false, null, e.getMessage()));
                failed++;
                log.warn("INSTITUTIONAL_GRANT_FAILED id={} name={} reason={}",
                        id, name, e.getMessage());
            }
        }

        log.info("INSTITUTIONAL_BATCH by={} requested={} granted={} failed={}",
                grantedBy, ids.size(), granted, failed);
        return new BatchResult(ids.size(), granted, failed, outcomes);
    }


    /* ══ internals ════════════════════════════════════════════ */

    /** C - 0001 / 26 — series letter, four-digit sequence, two-digit year. */
    private String nextCardNumber(LocalDate issuedAt) {
        Long next = repository.nextCardNumber();
        return "%s - %04d / %02d".formatted(SERIES, next, issuedAt.getYear() % 100);
    }

    /** 128 bits of randomness — guessing one is infeasible. */
    private String newVerificationToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The filing, if it belongs to this institution.
     *
     * ⚠️ NOT FOUND rather than FORBIDDEN when it does not: confirming that a
     * record exists is itself a disclosure, and one institution must not be
     * able to probe another's roll by id.
     */
    private InstitutionalCard ownedBy(Long id, Long institutionId) {
        InstitutionalCard card = repository.findById(id)
                .orElseThrow(() -> new InstitutionalCardNotFoundException(id));
        if (!card.getInstitutionId().equals(institutionId)) {
            throw new InstitutionalCardNotFoundException(id);
        }
        return card;
    }
}
