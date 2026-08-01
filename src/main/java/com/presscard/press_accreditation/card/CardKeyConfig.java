package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the card signing service from configuration.
 *
 * The same shape as JwtKeyConfig, for the same reason: CardSigningService
 * takes the three values it uses rather than the whole AppProperties tree, so
 * a unit test can hand it an in-memory keypair — and adding a field to the
 * configuration record does not break a test that never read it.
 *
 * That record has grown four times during this project. Each time, every
 * positional constructor in the test suite broke. This is the fix.
 */
@Configuration
public class CardKeyConfig {

    @Bean
    CardSigningService cardSigningService(AppProperties props) {
        return new CardSigningService(
                props.card().signingPrivateKeyLocation(),
                props.card().signingPublicKeyLocation(),
                props.card().signingKeyId());
    }
}
