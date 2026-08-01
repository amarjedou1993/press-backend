package com.presscard.press_accreditation.card;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature is HAPA's evidence that it issued a given card. If it can be
 * made to verify against altered data, it is evidence of nothing.
 *
 * Pure unit test — no Spring, no files, no AppProperties. The service takes a
 * KeyPair directly, so this class cannot break when the configuration record
 * grows another field.
 */
class CardSigningServiceTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private CardSigningService service() {
        return new CardSigningService(keyPair, "test-key");
    }

    private static final String CARD = "A - 0042 / 26";
    private static final String NNI = "1234567890";
    private static final String NAME = "Amar Ould Mohamed";
    private static final String ISSUED = "2026-08-01";
    private static final String EXPIRES = "2028-05-01";

    private static String canonical() {
        return CardSigningService.canonicalForm(CARD, NNI, NAME, ISSUED, EXPIRES);
    }

    @Test
    void aSignedCardVerifies() {
        CardSigningService svc = service();
        assertThat(svc.verify(canonical(), svc.sign(canonical()))).isTrue();
    }

    /**
     * The property the whole scheme rests on: change ANY field and the
     * signature must fail. A forger who alters a name, a number or an expiry
     * cannot produce a card that verifies.
     */
    @Test
    void alteringAnyField_breaksTheSignature() {
        CardSigningService svc = service();
        String signature = svc.sign(canonical());

        List<String> forgeries = List.of(
                CardSigningService.canonicalForm("A - 0043 / 26", NNI, NAME, ISSUED, EXPIRES),
                CardSigningService.canonicalForm(CARD, "9999999999", NAME, ISSUED, EXPIRES),
                CardSigningService.canonicalForm(CARD, NNI, "Someone Else", ISSUED, EXPIRES),
                CardSigningService.canonicalForm(CARD, NNI, NAME, "2020-01-01", EXPIRES),
                // The one a forger would most want: a later expiry.
                CardSigningService.canonicalForm(CARD, NNI, NAME, ISSUED, "2099-12-31"));

        for (String forged : forgeries) {
            assertThat(svc.verify(forged, signature))
                    .as("forged canonical: %s", forged)
                    .isFalse();
        }
    }

    @Test
    void aGarbageSignature_isRejectedRatherThanThrowing() {
        CardSigningService svc = service();

        // A malformed signature is an INVALID signature, not a server error —
        // the verification endpoint is public and must not be crashable.
        assertThat(svc.verify(canonical(), "not-base64!!")).isFalse();
        assertThat(svc.verify(canonical(), "")).isFalse();
        assertThat(svc.verify(canonical(), null)).isFalse();
    }

    @Test
    void theCanonicalFormIsStable() {
        // The same card must always produce the same bytes, or a signature
        // taken today would not verify tomorrow.
        assertThat(canonical()).isEqualTo(canonical());
        assertThat(canonical()).startsWith("HAPA-PRESS-CARD-V1|");
        assertThat(canonical()).contains(CARD).contains(NNI).contains(NAME);
    }

    @Test
    void aDifferentKey_cannotForgeASignature() throws Exception {
        CardSigningService hapa = service();

        // Someone with their own Ed25519 key signs the same card…
        KeyPair attacker = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String forged = new CardSigningService(attacker, "attacker").sign(canonical());

        // …and HAPA's key refuses it.
        assertThat(hapa.verify(canonical(), forged)).isFalse();
    }
}
