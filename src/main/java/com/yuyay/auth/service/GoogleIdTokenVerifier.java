package com.yuyay.auth.service;

import com.yuyay.exception.AuthFlowException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GoogleIdTokenVerifier {
    private static final String GOOGLE_KEYS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final Set<String> clientIds;
    private final Clock clock;
    private final Validator validator;
    private final NimbusJwtDecoder decoder;

    @Autowired
    public GoogleIdTokenVerifier(@Value("${app.google.client-ids:}") String clientIds, Clock clock,
                                 Validator validator) {
        this(clientIds, clock, validator, googleDecoder());
    }

    GoogleIdTokenVerifier(String clientIds, Clock clock, Validator validator, NimbusJwtDecoder decoder) {
        this.clientIds = Arrays.stream(clientIds.split(","))
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.clock = clock;
        this.validator = validator;
        this.decoder = decoder;
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        this.decoder.setJwtValidator(timestamps);
        this.decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(
                Map.of("iss", value -> value, "sub", value -> value)));
    }

    public GoogleIdentity verify(String idToken) {
        if (clientIds.isEmpty()) {
            throw unavailable();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (BadJwtException | IllegalArgumentException ex) {
            throw invalidToken();
        } catch (JwtException ex) {
            throw unavailable();
        }
        Map<String, Object> claims = jwt.getClaims();
        List<String> audience = jwt.getAudience();
        if (!(claims.get("iss") instanceof String issuer) || !GOOGLE_ISSUERS.contains(issuer)
                || audience == null || audience.isEmpty() || !clientIds.containsAll(audience)
                || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())
                || !Boolean.TRUE.equals(claims.get("email_verified"))
                || !(claims.get("sub") instanceof String subject) || subject.isBlank() || subject.length() > 255
                || !(claims.get("email") instanceof String rawEmail)) {
            throw invalidToken();
        }
        String email = rawEmail.toLowerCase(Locale.ROOT);
        GoogleIdentity identity = new GoogleIdentity(subject, email);
        if (!validator.validate(identity).isEmpty()) {
            throw invalidToken();
        }
        return identity;
    }

    private static NimbusJwtDecoder googleDecoder() {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(3));
        requests.setReadTimeout(Duration.ofSeconds(3));
        return NimbusJwtDecoder.withJwkSetUri(GOOGLE_KEYS)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requests))
                .build();
    }

    private static AuthFlowException invalidToken() {
        return new AuthFlowException(HttpStatus.UNAUTHORIZED, "GOOGLE_TOKEN_INVALID",
                "No se pudo verificar la identidad de Google");
    }

    private static AuthFlowException unavailable() {
        return new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_AUTH_UNAVAILABLE",
                "El acceso con Google no está disponible en este momento");
    }

    public record GoogleIdentity(String subject, @NotBlank @Email @Size(max = 150) String email) {}
}
