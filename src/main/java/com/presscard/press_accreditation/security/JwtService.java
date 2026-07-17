package com.presscard.press_accreditation.security;

import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates RS256 access tokens.
 *
 * Design notes:
 * - RS256 (asymmetric): only this backend holds the private key; anything
 *   holding the public key (future services, the Next.js backend) can VERIFY
 *   tokens without being able to MINT them.
 * - The parser is built once in the constructor (thread-safe, reusable) and
 *   pinned to this issuer: tokens signed for another system are rejected even
 *   if the key somehow matched.
 * - Claims kept minimal: subject = user id, plus email and role. Anyone can
 *   BASE64-decode a JWT — never put sensitive data in claims.
 */
@Service
public class JwtService {

    private final PrivateKey privateKey;
    private final String issuer;
    private final Duration ttl;
    private final JwtParser parser;

    public JwtService(PrivateKey jwtPrivateKey, PublicKey jwtPublicKey, AppProperties props) {
        this.privateKey = jwtPrivateKey;
        this.issuer = props.jwt().issuer();
        this.ttl = props.jwt().accessTokenTtl();
        this.parser = Jwts.parser()
                .verifyWith(jwtPublicKey)
                .requireIssuer(issuer)
                .build();
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * Parses and validates signature, issuer, and expiry in one call.
     * @throws JwtException for ANY invalid token (expired, tampered, wrong issuer)
     */
    public Claims parse(String token) throws JwtException {
        return parser.parseSignedClaims(token).getPayload();
    }

    public Instant expiresAt(Instant issuedAt) {
        return issuedAt.plus(ttl);
    }
}
