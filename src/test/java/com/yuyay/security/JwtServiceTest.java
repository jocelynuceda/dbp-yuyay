package com.yuyay.security;

import com.yuyay.config.AppProperties;
import com.yuyay.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private final JwtService jwtService = new JwtService(new AppProperties(
            new AppProperties.Jwt("test-secret-test-secret-test-secret-0123456789", 60, 7),
            "http://localhost:8080",
            new AppProperties.Cors(List.of("http://localhost:3000")),
            new AppProperties.Mail("Yuyay <test@test>", ""),
            new AppProperties.Admin("admin@yuyay.app", "Admin12345", "Administrador")));

    @Test
    void generatesAndParsesUserToken() {
        String token = jwtService.generateAccessToken(14L, "ana@yuyay.app", "USER");
        Claims claims = jwtService.parse(token);
        assertThat(claims.getSubject()).isEqualTo("14");
        assertThat(claims.get("email", String.class)).isEqualTo("ana@yuyay.app");
        assertThat(claims.get(JwtService.CLAIM_TYPE, String.class)).isEqualTo(JwtService.TYPE_USER);
    }

    @Test
    void delegateTokenExpiresWithDelegation() {
        String token = jwtService.generateDelegateToken(3L, 9L, "Enfermera Rosa", Instant.now().plusSeconds(3600));
        Claims claims = jwtService.parse(token);
        assertThat(claims.get(JwtService.CLAIM_TYPE, String.class)).isEqualTo(JwtService.TYPE_DELEGATE);
        assertThat(claims.get("careSubjectId", Long.class)).isEqualTo(9L);
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwtService.generateAccessToken(1L, "a@b.c", "USER");
        assertThatThrownBy(() -> jwtService.parse(token + "x")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = jwtService.generateDelegateToken(1L, 1L, "x", Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtService(new AppProperties(
                new AppProperties.Jwt("corto", 60, 7), "", new AppProperties.Cors(List.of()), new AppProperties.Mail("", ""),
                new AppProperties.Admin("admin@yuyay.app", "Admin12345", "Administrador"))))
                .isInstanceOf(IllegalStateException.class);
    }
}
