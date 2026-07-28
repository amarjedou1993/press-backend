package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Returns long-held dossiers to the pool.
 *
 * A claim is a lock, and every lock needs a way out. A reviewer takes a file
 * on Monday and is then ill for three weeks: without this, that candidate's
 * dossier sits untouched with nothing to detect it, while the session's
 * review phase runs out.
 *
 * The release is LOGGED and visible: this is not a silent correction, it is
 * an event an administrator should be able to see and question.
 */
@Component
public class StaleClaimReleaser {

    private static final Logger log = LoggerFactory.getLogger("REVIEW_AUDIT");

    private final ApplicationRepository applicationRepository;
    private final AppProperties props;

    public StaleClaimReleaser(ApplicationRepository applicationRepository,
                              AppProperties props) {
        this.applicationRepository = applicationRepository;
        this.props = props;
    }

    /** Nightly, at 03:00 — outside working hours, before the day begins. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void releaseStaleClaims() {
        int days = props.review().claimExpiryDays();
        if (days <= 0) {
            return;                                   // disabled
        }

        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        List<Application> stale = applicationRepository.findStaleClaims(cutoff);
        if (stale.isEmpty()) {
            return;
        }

        for (Application application : stale) {
            Long previousHolder = application.getClaimedBy();
            application.setClaimedBy(null);
            application.setClaimedAt(null);
            applicationRepository.save(application);

            log.warn("REVIEW_CLAIM_EXPIRED application={} heldBy={} since={} days={}",
                    application.getId(), previousHolder, cutoff, days);
        }

        log.info("REVIEW_STALE_CLAIMS_RELEASED count={} olderThanDays={}",
                stale.size(), days);
    }
}
