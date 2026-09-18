package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Telling an institution its cards are approaching expiry.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ AN INSTITUTION IS NOT CONVOKED THE WAY A JOURNALIST IS.
 *
 * A press card holder is invited when the Ministry opens a renewal session —
 * a window, with a deadline, the same for everyone. An institution has no
 * session: its cards expire on whatever date each grant set, and no window
 * exists to invite it into.
 *
 * So the trigger is the expiry itself. Ninety days out, the body is told how
 * many of its cards are involved and asked to re-file those employees who
 * still work there.
 *
 * ⚠️ AND IT IS TOLD ONCE PER WINDOW, NOT ONCE PER NIGHT.
 *
 * institutions.renewal_notified_at is the memory. Without it this job sends
 * the same message ninety times — and a body that receives ninety identical
 * notices stops reading notices from the Ministry, which is a worse outcome
 * than never having sent one.
 * ───────────────────────────────────────────────────────────────────────
 */
@Component
public class InstitutionalRenewalJob {

    private static final Logger log =
            LoggerFactory.getLogger("INSTITUTIONAL_RENEWAL_JOB");

    /**
     * ⚠️ NINETY DAYS, and it is a judgement rather than a constant of nature.
     *
     * Long enough for a body to assemble a roll, gather photographs and have
     * the Ministry grant before the cards lapse. Shorter, and a large
     * institution cannot finish; longer, and the notice arrives while the
     * current cards are plainly fine and is filed away.
     */
    private static final int HORIZON_DAYS = 90;

    /**
     * ⚠️ THIRTY DAYS BETWEEN NOTICES.
     *
     * So a body hears at ninety days, again at sixty, again at thirty — three
     * reminders across the window, which is what an administration sends and
     * what a recipient tolerates.
     */
    private static final int COOLING_OFF_DAYS = 30;

    private final InstitutionRepository institutionRepository;
    private final InstitutionalCardRepository cardRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public InstitutionalRenewalJob(InstitutionRepository institutionRepository,
                                   InstitutionalCardRepository cardRepository,
                                   UserRepository userRepository,
                                   EmailService emailService) {
        this.institutionRepository = institutionRepository;
        this.cardRepository = cardRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /**
     * ⚠️ ONCE A DAY, EARLY, AND NOT AT MIDNIGHT.
     *
     * 06:15 rather than 00:00: a notice timestamped in the middle of the
     * night reads as machinery, and the whole point is that it reads as the
     * Ministry writing to a body.
     *
     * ⚠️ SINGLE INSTANCE ASSUMED, like SessionPhaseJob. Two instances would
     * send two notices — recoverable, but ShedLock is the answer if the
     * deployment ever grows.
     */
    @Scheduled(cron = "0 15 6 * * *")
    @Transactional
    public void notifyApproachingExpiry() {
        LocalDate horizon = LocalDate.now().plusDays(HORIZON_DAYS);
        OffsetDateTime coolingOff =
                OffsetDateTime.now().minusDays(COOLING_OFF_DAYS);

        int notified = 0;

        for (Institution institution : institutionRepository.findByActiveTrueOrderByNameFrAsc()) {

            // ⚠️ Silent when the body was told recently. NULL is "never", and
            // never is due.
            if (institution.getRenewalNotifiedAt() != null
                    && institution.getRenewalNotifiedAt().isAfter(coolingOff)) {
                continue;
            }

            List<InstitutionalCard> due =
                    cardRepository.findApproachingExpiry(institution.getId(), horizon);
            if (due.isEmpty()) {
                continue;
            }

            /*
             * ⚠️ THE ACCOUNT, NOT A PERSON.
             *
             * The address belongs to the body — that is the whole arrangement
             * — so a departing officer never takes the notice with them. A
             * body with no account yet is skipped in silence: it cannot act on
             * the message, and the Ministry's own screen already shows it as
             * unable to file.
             */
            User account = userRepository
                    .findByRoleAndInstitutionId(UserRole.INSTITUTION, institution.getId())
                    .filter(User::isEnabled)
                    .orElse(null);

            if (account == null) {
                log.warn("INSTITUTIONAL_RENEWAL_NO_ACCOUNT institution={} due={}",
                        institution.getCode(), due.size());
                continue;
            }

            emailService.sendInstitutionalRenewalDue(
                    account.getEmail(),
                    institution.getNameFr(),
                    due.size(),
                    due.get(0).getExpiresAt());

            institution.setRenewalNotifiedAt(OffsetDateTime.now());
            institutionRepository.save(institution);
            notified++;

            log.info("INSTITUTIONAL_RENEWAL_NOTICE institution={} cards={} earliest={}",
                    institution.getCode(), due.size(), due.get(0).getExpiresAt());
        }

        if (notified > 0) {
            log.info("INSTITUTIONAL_RENEWAL_JOB notified={}", notified);
        }
    }
}