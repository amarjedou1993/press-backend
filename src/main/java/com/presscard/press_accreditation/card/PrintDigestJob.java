package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.honour.HonourCardRepository;
import com.presscard.press_accreditation.institutional.InstitutionalCardRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Telling the producer what is waiting.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ ONE MESSAGE A DAY, NOT ONE PER CARD.
 *
 * A producer does not wait for individual cards. They open the screen and
 * take whatever is producible — which is why that screen is a queue with
 * counts rather than a list of alerts. A message per card would send two
 * hundred for work done in one download.
 *
 * But nothing told them anything was waiting. A card granted on Monday sat
 * until whenever they next signed in, and "I check on Fridays" is a
 * reasonable habit that costs four days.
 *
 * ⚠️ SENT ONLY WHEN SOMETHING WAS GRANTED SINCE THE LAST DIGEST.
 *
 * Not when the total changed: three produced and three granted leaves the
 * total alone, and three people wait for cards nobody knows are waiting. The
 * question is "anything new", and the watermark answers it.
 *
 * ⚠️ AND NEVER WHEN THE QUEUE IS EMPTY. A daily "0 cartes prêtes" is how a
 * recipient learns to filter a sender.
 * ───────────────────────────────────────────────────────────────────────
 */
@Component
public class PrintDigestJob {

    private static final Logger log = LoggerFactory.getLogger("PRINT_DIGEST_JOB");

    private final CardRepository cardRepository;
    private final HonourCardRepository honourCardRepository;
    private final InstitutionalCardRepository institutionalCardRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public PrintDigestJob(CardRepository cardRepository,
                          HonourCardRepository honourCardRepository,
                          InstitutionalCardRepository institutionalCardRepository,
                          UserRepository userRepository,
                          EmailService emailService) {
        this.cardRepository = cardRepository;
        this.honourCardRepository = honourCardRepository;
        this.institutionalCardRepository = institutionalCardRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /**
     * ⚠️ 07:00, SO IT IS READ AT THE START OF A DAY'S WORK.
     *
     * A digest timestamped at three in the morning arrives above the night's
     * spam and below nothing. At seven it is the first thing in the inbox
     * when the workshop opens, which is when it can be acted on.
     *
     * ⚠️ SINGLE INSTANCE ASSUMED, like the other jobs here. Two instances
     * would send two digests — an annoyance rather than a fault, and ShedLock
     * is the answer if the deployment ever grows.
     */
    @Scheduled(cron = "0 0 7 * * *")
    @Transactional
    public void sendDigests() {
        /*
         * ⚠️ COUNTED ONCE, NOT PER RECIPIENT.
         *
         * Every producer sees the same queue — there is one. Counting inside
         * the loop would ask the same three questions of the database for
         * each account, for an answer that cannot differ between them.
         */
        long sessionCards = cardRepository.countProducible(CardStatus.VALID);
        long honourCards = honourCardRepository.countProducible(CardStatus.VALID);
        long institutionalCards =
                institutionalCardRepository.countProducible(CardStatus.VALID);

        long total = sessionCards + honourCards + institutionalCards;
        if (total == 0) {
            return;
        }

        int sent = 0;

        for (User producer : userRepository.findByRoleAndEnabledTrue(UserRole.PRINTER)) {
            OffsetDateTime since = producer.getPrintDigestSentAt();

            boolean somethingNew = since == null
                    || cardRepository.existsIssuedAfter(since.toLocalDate(), CardStatus.VALID)
                    || honourCardRepository.existsGrantedAfter(since.toLocalDate(), CardStatus.VALID)
                    /* ⚠️ An instant here: institutional_cards.granted_at is a
                       timestamp, because a filing and its grant are genuinely
                       different moments. */
                    || institutionalCardRepository.existsGrantedAfter(since);

            emailService.sendPrintDigest(
                    producer.getEmail(),
                    sessionCards, honourCards, institutionalCards);

            producer.setPrintDigestSentAt(OffsetDateTime.now());
            userRepository.save(producer);
            sent++;
        }

        if (sent > 0) {
            log.info("PRINT_DIGEST sent={} session={} honour={} institutional={}",
                    sent, sessionCards, honourCards, institutionalCards);
        }
    }
}
