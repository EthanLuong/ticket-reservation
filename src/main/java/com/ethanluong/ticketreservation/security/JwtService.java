package com.ethanluong.ticketreservation.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * HS256-signed JWTs. Subject is the user's UUID; email carried as a custom claim.
 *
 * For Phase 1+, rotate to RS256 with a JWKS endpoint if other services need to
 * verify tokens without sharing the secret.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties props;

    private SecretKey signingKey() {
        byte[] bytes = props.secret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(bytes);
    }

    public String issue(UUID userId, String email) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofMinutes(props.expirationMinutes()));
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(signingKey(), Jwts.SIG.HS256)
                .compact();
    }

    public long expirationSeconds() {
        return Duration.ofMinutes(props.expirationMinutes()).getSeconds();
    }

    /** Returns parsed claims if valid; throws JwtException otherwise. */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(signingKey())
                .requireIssuer(props.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
