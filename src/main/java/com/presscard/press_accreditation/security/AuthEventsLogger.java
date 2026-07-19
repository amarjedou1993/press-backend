package com.presscard.press_accreditation.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Structured audit trail for authentication, via Spring Security's own
 * event stream — no code in the login path, nothing to forget.
 *
 * For a regulatory authority, "who tried to sign in, when, and with what
 * outcome" is evidence. These lines are grep-able (AUTH_OK / AUTH_FAIL)
 * and NEVER contain passwords.
 */
@Component
public class AuthEventsLogger {

    private static final Logger log = LoggerFactory.getLogger("AUTH_AUDIT");

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        log.info("AUTH_OK user={}", event.getAuthentication().getName());
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        log.warn("AUTH_FAIL user={} reason={}",
                event.getAuthentication().getName(),
                event.getException().getClass().getSimpleName());
    }
}
