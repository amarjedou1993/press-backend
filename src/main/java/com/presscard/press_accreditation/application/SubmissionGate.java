//package com.presscard.press_accreditation.application;
//
//import com.presscard.press_accreditation.document.CompletenessService;
//import com.presscard.press_accreditation.profile.CandidateProfile;
//import com.presscard.press_accreditation.profile.CandidateProfileRepository;
//import com.presscard.press_accreditation.session.Session;
//import com.presscard.press_accreditation.session.SessionRepository;
//import com.presscard.press_accreditation.session.SessionStatus;
//import com.presscard.press_accreditation.user.User;
//import com.presscard.press_accreditation.user.UserRepository;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.LocalDate;
//import java.time.format.DateTimeFormatter;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Locale;
//
//@Service
//public class SubmissionGate {
//
//    /** One blocking condition, with the French explanation the UI shows. */
//    public record Blocker(Reason reason, String messageFr) {
//        public enum Reason {
//            SESSION_NOT_RECEIVING,
//            DEADLINE_PASSED,
//            PROFILE_INCOMPLETE,
//            EMAIL_NOT_VERIFIED,
//            DOCUMENTS_INCOMPLETE,
//            /** Printed on the card as التخصص — no card without it. */
//            SPECIALISATION_MISSING,
//            /** Printed on the card as المؤسسة — no card without it. */
//            INSTITUTION_MISSING
//        }
//    }
//
//    public record GateResult(
//            boolean allowed,
//            List<Blocker> blockers,
//            CompletenessService.CompletenessResult completeness
//    ) {}
//
//    private final SessionRepository sessionRepository;
//    private final UserRepository userRepository;
//    private final CandidateProfileRepository profileRepository;
//    private final CompletenessService completenessService;
//
//    public SubmissionGate(SessionRepository sessionRepository,
//                          UserRepository userRepository,
//                          CandidateProfileRepository profileRepository,
//                          CompletenessService completenessService) {
//        this.sessionRepository = sessionRepository;
//        this.userRepository = userRepository;
//        this.profileRepository = profileRepository;
//        this.completenessService = completenessService;
//    }
//
//    @Transactional(readOnly = true)
//    public GateResult evaluate(Application application) {
//        List<Blocker> blockers = new ArrayList<>();
//
//        /* ── 1 & 2. the session ── */
//        Session session = sessionRepository.findById(application.getSessionId())
//                .orElseThrow(() -> new IllegalStateException(
//                        "Application " + application.getId() + " references a missing session"));
//
//        if (session.getStatus() != SessionStatus.RECEIVING) {
//            blockers.add(new Blocker(
//                    Blocker.Reason.SESSION_NOT_RECEIVING,
//                    "La session n'accepte plus de candidatures."));
//        } else if (LocalDate.now().isAfter(session.getReceivingEnd())) {
//            // BINDING deadline — see the class javadoc.
//            blockers.add(new Blocker(
//                    Blocker.Reason.DEADLINE_PASSED,
//                    "La date limite de dépôt était le %s.".formatted(
//                            formatFr(session.getReceivingEnd()))));
//        }
//
//        /* ── 3 & 4. the candidate ── */
//        User candidate = userRepository.findById(application.getCandidateId())
//                .orElseThrow(() -> new IllegalStateException(
//                        "Application " + application.getId() + " references a missing user"));
//
//        if (!candidate.isEmailVerified()) {
//            blockers.add(new Blocker(
//                    Blocker.Reason.EMAIL_NOT_VERIFIED,
//                    "Vérifiez votre adresse e-mail avant de soumettre votre dossier."));
//        }
//
//        boolean profileComplete = profileRepository
//                .findById(application.getCandidateId())
//                .map(CandidateProfile::isComplete)
//                .orElse(false);
//        if (!profileComplete) {
//            blockers.add(new Blocker(
//                    Blocker.Reason.PROFILE_INCOMPLETE,
//                    "Complétez votre profil (identité, date et lieu de naissance, "
//                  + "photographie) avant de soumettre."));
//        }
//
//        /* ── 5 & 6. what the card needs ──
//           Both are printed on the credential. A dossier without them can be
//           approved by the commission and then fail at issuance, which is the
//           one moment nobody can do anything about it. */
//        if (application.getSpecialisationId() == null) {
//            blockers.add(new Blocker(
//                    Blocker.Reason.SPECIALISATION_MISSING,
//                    "Indiquez votre spécialité : elle figure sur la carte de presse."));
//        }
//        if (application.getInstitution() == null || application.getInstitution().isBlank()) {
//            blockers.add(new Blocker(
//                    Blocker.Reason.INSTITUTION_MISSING,
//                    "Indiquez l'organe de presse pour lequel vous exercez : il figure "
//                  + "sur la carte de presse."));
//        }
//
//        /* ── 7. the documents ── */
//        CompletenessService.CompletenessResult completeness =
//                completenessService.evaluate(application.getId(), application.getCategoryId());
//        if (!completeness.complete()) {
//            blockers.add(new Blocker(
//                    Blocker.Reason.DOCUMENTS_INCOMPLETE,
//                    "Votre dossier est incomplet : " + String.join(" ", completeness.missingFr())));
//        }
//
//        return new GateResult(blockers.isEmpty(), blockers, completeness);
//    }
//
//    private static String formatFr(LocalDate date) {
//        return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH));
//    }
//}



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
 * Whether a dossier may be submitted, and if not, exactly why.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE REASON IS THE ANSWER; THE SENTENCE IS A CONVENIENCE.
 *
 * Every blocker already carried a machine-readable `Reason`. That enum is the
 * right thing for a bilingual interface to key off — one constant, two
 * catalogues, and wording changed without a deployment.
 *
 * `messageFr` and `messageAr` remain for logs, e-mails, and any consumer that
 * wants a finished sentence. But THE FRONTEND SHOULD USE `reason`, because of
 * the deadline:
 *
 *   "La date limite de dépôt était le 15 mars 2026."
 *
 * That date was formatted here, in French, with Locale.FRENCH. Dropped into
 * an Arabic screen it is a French date in an Arabic sentence — the precise
 * failure the bilingual work exists to prevent.
 *
 * So the DATE IS SENT AS DATA, in `deadline`, and the reader's own page
 * formats it. The pre-composed sentences never carry a formatted value that
 * the display side could not have produced itself.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class SubmissionGate {

    /**
     * One blocking condition.
     *
     * @param reason    the machine-readable cause — THE TRANSLATION KEY
     * @param messageFr a finished French sentence, for logs and e-mails
     * @param messageAr the same in Arabic
     * @param deadline  set only on DEADLINE_PASSED, so the display side can
     *                  format it in the reader's locale rather than receiving
     *                  a French string it cannot unpick
     */
    public record Blocker(
            Reason reason,
            String messageFr,
            String messageAr,
            LocalDate deadline
    ) {
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

        /** Most blockers carry no parameter. */
        public static Blocker of(Reason reason, String fr, String ar) {
            return new Blocker(reason, fr, ar, null);
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
            blockers.add(Blocker.of(
                    Blocker.Reason.SESSION_NOT_RECEIVING,
                    "La session n'accepte plus de candidatures.",
                    "لم تعد الدورة تقبل الترشيحات."));
        } else if (LocalDate.now().isAfter(session.getReceivingEnd())) {
            // BINDING deadline — see the class javadoc.
            //
            // The date travels as DATA in the fourth argument. The sentences
            // are for logs; a screen formats the LocalDate in its own locale.
            blockers.add(new Blocker(
                    Blocker.Reason.DEADLINE_PASSED,
                    "La date limite de dépôt était le %s.".formatted(
                            formatFr(session.getReceivingEnd())),
                    "كان آخر أجل للإيداع %s.".formatted(
                            formatAr(session.getReceivingEnd())),
                    session.getReceivingEnd()));
        }

        /* ── 3 & 4. the candidate ── */
        User candidate = userRepository.findById(application.getCandidateId())
                .orElseThrow(() -> new IllegalStateException(
                        "Application " + application.getId() + " references a missing user"));

        if (!candidate.isEmailVerified()) {
            blockers.add(Blocker.of(
                    Blocker.Reason.EMAIL_NOT_VERIFIED,
                    "Vérifiez votre adresse e-mail avant de soumettre votre dossier.",
                    "تحقق من بريدك الإلكتروني قبل إيداع ملفك."));
        }

        boolean profileComplete = profileRepository
                .findById(application.getCandidateId())
                .map(CandidateProfile::isComplete)
                .orElse(false);
        if (!profileComplete) {
            blockers.add(Blocker.of(
                    Blocker.Reason.PROFILE_INCOMPLETE,
                    "Complétez votre profil (identité, date et lieu de naissance, "
                  + "photographie) avant de soumettre.",
                    "أكمل ملفك الشخصي (الهوية، تاريخ ومكان الميلاد، الصورة) "
                  + "قبل الإيداع."));
        }

        /* ── 5 & 6. what the card needs ──
           Both are printed on the credential. A dossier without them can be
           approved by the commission and then fail at issuance, which is the
           one moment nobody can do anything about it. */
        if (application.getSpecialisationId() == null) {
            blockers.add(Blocker.of(
                    Blocker.Reason.SPECIALISATION_MISSING,
                    "Indiquez votre spécialité : elle figure sur la carte de presse.",
                    "حدد تخصصك: فهو مدوّن على البطاقة الصحفية."));
        }
        if (application.getInstitution() == null || application.getInstitution().isBlank()) {
            blockers.add(Blocker.of(
                    Blocker.Reason.INSTITUTION_MISSING,
                    "Indiquez l'organe de presse pour lequel vous exercez : il figure "
                  + "sur la carte de presse.",
                    "حدد المؤسسة الصحفية التي تعمل بها: فهي مدوّنة على البطاقة "
                  + "الصحفية."));
        }

        /* ── 7. the documents ──
           The composed sentence is a convenience for logs. A SCREEN should
           render the per-requirement detail from `completeness`, which it
           already receives: "your dossier is incomplete" plus a list is a
           worse answer than the checklist that names each missing piece. */
        CompletenessService.CompletenessResult completeness =
                completenessService.evaluate(application.getId(), application.getCategoryId());
        if (!completeness.complete()) {
            blockers.add(Blocker.of(
                    Blocker.Reason.DOCUMENTS_INCOMPLETE,
                    "Votre dossier est incomplet : "
                            + String.join(" ", completeness.missingFr()),
                    "ملفك غير مكتمل: "
                            + String.join(" ", completeness.missingAr())));
        }

        return new GateResult(blockers.isEmpty(), blockers, completeness);
    }

    private static String formatFr(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH));
    }

    /**
     * ⚠️ Locale.forLanguageTag("ar"), not "ar-MR".
     *
     * The country tag pulls in eastern digits ٢٠٢٦ on some JDKs. The printed
     * card uses Western digits, and a date the holder reads against the card
     * must match it.
     */
    private static String formatAr(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern(
                "d MMMM yyyy", Locale.forLanguageTag("ar")));
    }
}
