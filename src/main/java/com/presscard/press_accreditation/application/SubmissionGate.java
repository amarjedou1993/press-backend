package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.document.CompletenessService;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.session.SessionStatus;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The four conditions an application must satisfy before it can be submitted.
 * Gathered in ONE place so the rule is auditable, testable, and impossible to
 * partially apply:
 *
 *   1. the session is RECEIVING            — candidatures are open at all
 *   2. today <= receiving_end              — the PUBLISHED deadline is BINDING
 *   3. the candidate's profile is complete — identity is on file
 *   4. the e-mail address is verified      — we can reach them, and the
 *                                            identity claim is corroborated
 *   5. the documents satisfy the category  — CompletenessService
 *
 * On rule 2: the deadline holds even while an admin has not yet clicked
 * "advance phase". Otherwise whether a late submission is accepted would
 * depend on how quickly an administrator happened to act — two candidates
 * equally late, different outcomes, decided by accident. For a regulator
 * whose decisions can be challenged, the published date must mean what it says.
 * If HAPA wants to give more time, the honest mechanism is changing the date.
 *
 * The result lists EVERY unmet condition rather than failing on the first, so
 * the candidate can fix everything in one pass instead of discovering problems
 * one at a time.
 */
@Service
public class SubmissionGate {

    /** One blocking condition, with the French explanation the UI shows. */
    public record Blocker(Reason reason, String messageFr) {
        public enum Reason {
            SESSION_NOT_RECEIVING,
            DEADLINE_PASSED,
            PROFILE_INCOMPLETE,
            EMAIL_NOT_VERIFIED,
            DOCUMENTS_INCOMPLETE
        }
    }

    public record GateResult(
            boolean allowed,
            List<Blocker> blockers,
            CompletenessService.CompletenessResult completeness
    ) {}

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;
    private final CompletenessService completenessService;

    public SubmissionGate(SessionRepository sessionRepository,
                          UserRepository userRepository,
                          CandidateProfileRepository profileRepository,
                          CompletenessService completenessService) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.completenessService = completenessService;
    }

    @Transactional(readOnly = true)
    public GateResult evaluate(Application application) {
        List<Blocker> blockers = new ArrayList<>();

        /* ── 1 & 2. the session ── */
        Session session = sessionRepository.findById(application.getSessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Application " + application.getId() + " references a missing session"));

        if (session.getStatus() != SessionStatus.RECEIVING) {
            blockers.add(new Blocker(
                    Blocker.Reason.SESSION_NOT_RECEIVING,
                    "La session n'accepte plus de candidatures."));
        } else if (LocalDate.now().isAfter(session.getReceivingEnd())) {
            // BINDING deadline — see the class javadoc.
            blockers.add(new Blocker(
                    Blocker.Reason.DEADLINE_PASSED,
                    "La date limite de dépôt était le %s.".formatted(
                            formatFr(session.getReceivingEnd()))));
        }

        /* ── 3 & 4. the candidate ── */
        User candidate = userRepository.findById(application.getCandidateId())
                .orElseThrow(() -> new IllegalStateException(
                        "Application " + application.getId() + " references a missing user"));

        if (!candidate.isEmailVerified()) {
            blockers.add(new Blocker(
                    Blocker.Reason.EMAIL_NOT_VERIFIED,
                    "Vérifiez votre adresse e-mail avant de soumettre votre dossier."));
        }

        boolean profileComplete = profileRepository
                .findById(application.getCandidateId())
                .map(p -> p.isComplete())
                .orElse(false);
        if (!profileComplete) {
            blockers.add(new Blocker(
                    Blocker.Reason.PROFILE_INCOMPLETE,
                    "Complétez votre profil (identité, date et lieu de naissance) avant de soumettre."));
        }

        /* ── 5. the documents ── */
        CompletenessService.CompletenessResult completeness =
                completenessService.evaluate(application.getId(), application.getCategoryId());
        if (!completeness.complete()) {
            blockers.add(new Blocker(
                    Blocker.Reason.DOCUMENTS_INCOMPLETE,
                    "Votre dossier est incomplet : " + String.join(" ", completeness.missingFr())));
        }

        return new GateResult(blockers.isEmpty(), blockers, completeness);
    }

    private static String formatFr(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter
                .ofPattern("d MMMM yyyy", java.util.Locale.FRENCH));
    }
}
