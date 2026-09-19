package com.yuyay.security;

import com.yuyay.config.AppProperties;
import com.yuyay.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_USER = "USER";
    public static final String TYPE_DELEGATE = "DELEGATE";

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(AppProperties properties) {
        String secret = properties.jwt().secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(properties.jwt().accessExpirationMinutes());
    }

    public String generateAccessToken(Long userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claims(Map.of(CLAIM_TYPE, TYPE_USER, "email", email, "role", role))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    public String generateDelegateToken(Long delegationId, Long careSubjectId, String granteeName, Instant validUntil) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("delegation:" + delegationId)
                .claims(Map.of(CLAIM_TYPE, TYPE_DELEGATE,
                        "delegationId", delegationId,
                        "careSubjectId", careSubjectId,
                        "granteeName", granteeName))
                .issuedAt(Date.from(now))
                .expiration(Date.from(validUntil))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Token inválido o expirado");
        }
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
