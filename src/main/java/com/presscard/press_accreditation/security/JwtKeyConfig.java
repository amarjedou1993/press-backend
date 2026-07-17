package com.presscard.press_accreditation.security;

import com.presscard.press_accreditation.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the RSA keypair from the PEM locations configured in app.jwt.*.
 *
 * Why a separate config class instead of loading inside JwtService:
 * JwtService takes plain PrivateKey/PublicKey in its constructor, so unit
 * tests can hand it an in-memory KeyPair — no files, no Spring context.
 * Key loading (an infrastructure concern) stays here; token logic stays there.
 *
 * Expected formats (exactly what `openssl genpkey` / `openssl pkey -pubout`
 * produce): PKCS#8 "BEGIN PRIVATE KEY" and X.509 "BEGIN PUBLIC KEY".
 */
@Configuration
public class JwtKeyConfig {

    @Bean
    RSAPrivateKey jwtPrivateKey(AppProperties props) throws Exception {
        byte[] der = readPem(props.jwt().privateKeyLocation(), "PRIVATE KEY");
        return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    @Bean
    RSAPublicKey jwtPublicKey(AppProperties props) throws Exception {
        byte[] der = readPem(props.jwt().publicKeyLocation(), "PUBLIC KEY");
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
    }

    private byte[] readPem(Resource location, String expectedType) throws Exception {
        String pem = location.getContentAsString(StandardCharsets.UTF_8);
        if (!pem.contains("BEGIN " + expectedType)) {
            throw new IllegalStateException(
                    "Key at " + location + " is not a " + expectedType + " PEM. "
                    + "Generate dev keys with the openssl commands documented in application-dev.yaml.");
        }
        String base64 = pem
                .replaceAll("-----BEGIN " + expectedType + "-----", "")
                .replaceAll("-----END " + expectedType + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
