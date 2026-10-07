package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Tells the frontend to drop its cached public pages when session state
 * changes, so /sessions and / reflect an admin's action within a second
 * instead of up to a revalidation window.
 *
 * Three deliberate properties:
 *
 *  · ASYNCHRONOUS — the admin's transaction must never wait on, or fail
 *    because of, the frontend. A phase advance is a regulatory act; a cache
 *    purge is a convenience.
 *  · FAILURE-TOLERANT — if the frontend is down or the token is wrong, we
 *    log and move on. The page still self-heals when its ISR window expires,
 *    so the worst case is the behaviour we had before.
 *  · OPTIONAL — disabled by config (and off by default in tests), so nothing
 *    calls out to a frontend that isn't there.
 *
 * HTTP/1.1 ONLY. The default JDK HttpClient opens plain-http calls with an
 * HTTP/2 upgrade request (h2c). The Next.js standalone server does not accept
 * that upgrade and closes the connection without a response ("header parser
 * received no bytes"). HttpURLConnection speaks HTTP/1.1 only, so the purge
 * reaches the frontend inside Docker.
 */
@Component
public class PublicCacheNotifier {

    private static final Logger log = LoggerFactory.getLogger(PublicCacheNotifier.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient client;
    private final AppProperties.Revalidation config;

    public PublicCacheNotifier(AppProperties props, RestClient.Builder builder) {
        this.config = props.revalidation();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        this.client = builder
                .requestFactory(requestFactory)
                .build();
    }

    /** Fire-and-forget purge of the session-dependent public pages. */
    @Async
    public void notifySessionsChanged() {
        if (!config.enabled()) {
            return;
        }
        try {
            client.post()
                    .uri(config.url())
                    .header("X-Revalidate-Token", config.token())
                    .header("Content-Type", "application/json")
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Public cache purge requested at {}", config.url());
        } catch (Exception e) {
            // Never propagate: the public page self-heals on its own window.
            log.warn("Public cache purge failed ({}). The page will refresh on its"
                    + " normal revalidation window. Cause: {}", config.url(), e.getMessage());
        }
    }
}