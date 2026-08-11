package com.presscard.press_accreditation.card;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Signs cards, and proves later that HAPA issued them.
 *
 * ⚠️ THIS KEY IS NOT THE JWT KEY, and the distinction is not stylistic.
 *
 * A JWT signing key SHOULD rotate periodically — that is basic hygiene, and
 * nothing breaks, because tokens live for hours. A CARD signature must stay
 * verifiable for the card's entire life. Sign cards with a rotating key and
 * every card in circulation becomes unverifiable the day it rotates.
 *
 * So: a dedicated keypair, never rotated while cards signed by it remain in
 * force, and each card records WHICH key signed it so a future rotation stays
 * backwards-verifiable.
 *
 * WHAT THE SIGNATURE IS FOR. Verification is online, so the signature is not
 * what secures the lookup — the opaque token does that. The signature is
 * EVIDENCE: if a card's authenticity is disputed, HAPA can demonstrate it
 * issued that exact card against a published public key, without asking
 * anyone to trust its database.
 *
 * Ed25519 rather than RSA: 64-byte signatures, a one-line openssl command,
 * and a visibly different algorithm from the JWT key — so the two can never
 * be confused for one another in configuration.
 *
 * NOTE ON THE CONSTRUCTOR. It takes the three values it uses, NOT AppProperties.
 * The bean is built in CardKeyConfig. That way a unit test can hand it an
 * in-memory keypair, and this class does not break every time the
 * configuration record grows a field — which it has done four times already.
 */
public class CardSigningService {

    private static final Logger log = LoggerFactory.getLogger(CardSigningService.class);
    private static final String ALGORITHM = "Ed25519";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String keyId;

    public CardSigningService(Resource privateKeyPem, Resource publicKeyPem, String keyId) {
        try {
            byte[] priv = readPem(privateKeyPem.getContentAsString(StandardCharsets.UTF_8),
                    "PRIVATE KEY");
            byte[] pub = readPem(publicKeyPem.getContentAsString(StandardCharsets.UTF_8),
                    "PUBLIC KEY");

            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            this.privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(priv));
            this.publicKey = factory.generatePublic(new X509EncodedKeySpec(pub));
            this.keyId = keyId;

            log.info("Card signing key loaded: id={} algorithm={}", keyId, ALGORITHM);

        } catch (Exception e) {
            throw new IllegalStateException("""
                    The card signing key could not be loaded.

                    Cards carry a signature that must remain verifiable for their
                    whole life, so this key is SEPARATE from the JWT key and must
                    never be rotated while issued cards are still in force.

                    Generate a development pair with:
                        openssl genpkey -algorithm ed25519 -out dev-card-private.pem
                        openssl pkey -in dev-card-private.pem -pubout -out dev-card-public.pem
                    """, e);
        }
    }

    /** For tests: an in-memory keypair, no files. */
    public CardSigningService(KeyPair keyPair, String keyId) {
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();
        this.keyId = keyId;
    }

    /**
     * The canonical string a signature covers.
     *
     * Field order is FIXED and the separator cannot appear in any field, so
     * the same card always produces the same bytes. A signature over a
     * loosely formatted string is a signature over nothing in particular.
     */
    public static String canonicalForm(String cardNumber, String nni, String fullName,
                                       String issuedAt, String expiresAt) {
        return String.join("|",
                "RIM-PRESS-CARD-V1",
                cardNumber,
                nni == null ? "" : nni,
                fullName == null ? "" : fullName.trim(),
                issuedAt,
                expiresAt);
    }

    /** Sign a card. Base64url, so it is safe anywhere it might be copied. */
    public String sign(String canonical) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Card signing failed", e);
        }
    }

    /** Verify one — used by the verification page and by any dispute. */
    public boolean verify(String canonical, String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getUrlDecoder().decode(signatureBase64));
        } catch (Exception e) {
            // A malformed signature is an invalid signature, not an error.
            return false;
        }
    }

    /** Which key is signing today — recorded on every card issued. */
    public String currentKeyId() {
        return keyId;
    }

    /**
     * The public key, published so verification never depends on trusting
     * HAPA's servers — anyone may check a signature independently.
     */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    private static byte[] readPem(String pem, String type) {
        if (!pem.contains("BEGIN " + type)) {
            throw new IllegalStateException("Not a " + type + " PEM");
        }
        String base64 = pem
                .replaceAll("-----BEGIN " + type + "-----", "")
                .replaceAll("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
