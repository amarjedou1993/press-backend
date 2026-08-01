package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.document.CompletenessService;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.session.SessionStatus;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The single place that decides whether a dossier may be submitted.
 *
 * IT RETURNS EVERY BLOCKER, NOT THE FIRST. A candidate told "your profile is
 * incomplete", who fixes it and is then told "your e-mail is unverified", and
 * then "a document is missing", has been sent away three times for one
 * problem. All seven conditions are evaluated, always.
 *
 * THE DEADLINE IS BINDING. Past receiving_end no submission is accepted, even
 * while the session still displays RECEIVING because nobody has advanced the
 * phase. The date the candidate was told is the date that holds — an
 * administrator's timing must never extend or shorten it.
 *
 * TWO CONDITIONS EXIST BECAUSE OF THE CARD. Specialisation and institution are
 * printed on the credential, so a dossier lacking them cannot produce one.
 * Discovering that AT ISSUANCE — after the commission has approved and the
 * candidate has been told they succeeded — is the worst possible moment. The
 * gate refuses it up front, and can explain why; a NOT NULL constraint could
 * do neither.
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
            DOCUMENTS_INCOMPLETE,
            /** Printed on the card as التخصص — no card without it. */
            SPECIALISATION_MISSING,
            /** Printed on the card as المؤسسة — no card without it. */
            INSTITUTION_MISSING
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
                .map(CandidateProfile::isComplete)
                .orElse(false);
        if (!profileComplete) {
            blockers.add(new Blocker(
                    Blocker.Reason.PROFILE_INCOMPLETE,
                    "Complétez votre profil (identité, date et lieu de naissance, "
                  + "photographie) avant de soumettre."));
        }

        /* ── 5 & 6. what the card needs ──
           Both are printed on the credential. A dossier without them can be
           approved by the commission and then fail at issuance, which is the
           one moment nobody can do anything about it. */
        if (application.getSpecialisationId() == null) {
            blockers.add(new Blocker(
                    Blocker.Reason.SPECIALISATION_MISSING,
                    "Indiquez votre spécialité : elle figure sur la carte de presse."));
        }
        if (application.getInstitution() == null || application.getInstitution().isBlank()) {
            blockers.add(new Blocker(
                    Blocker.Reason.INSTITUTION_MISSING,
                    "Indiquez l'organe de presse pour lequel vous exercez : il figure "
                  + "sur la carte de presse."));
        }

        /* ── 7. the documents ── */
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
        return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH));
    }
}
