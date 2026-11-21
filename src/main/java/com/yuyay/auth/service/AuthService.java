package com.yuyay.auth.service;

import com.yuyay.auth.dto.AuthResponseDTO;
import com.yuyay.auth.dto.GoogleAuthRequestDTO;
import com.yuyay.auth.dto.LoginRequestDTO;
import com.yuyay.auth.dto.RegisterRequestDTO;
import com.yuyay.auth.service.GoogleIdTokenVerifier.GoogleIdentity;
import com.yuyay.config.AppProperties;
import com.yuyay.event.UserRegisteredEvent;
import com.yuyay.exception.AuthFlowException;
import com.yuyay.exception.DuplicateEmailException;
import com.yuyay.exception.InvalidCredentialsException;
import com.yuyay.exception.InvalidTokenException;
import com.yuyay.security.JwtService;
import com.yuyay.security.TokenHasher;
import com.yuyay.user.entity.RefreshToken;
import com.yuyay.user.entity.Role;
import com.yuyay.user.entity.User;
import com.yuyay.user.mapper.UserMapper;
import com.yuyay.user.repository.RefreshTokenRepository;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final AppProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final TermsService termsService;
    private final Clock clock;

    @Transactional
    public AuthResponseDTO register(RegisterRequestDTO dto) {
        String version = termsService.requireCurrentAcceptance(dto.termsAccepted(), dto.termsVersion());
        if (userRepository.existsByEmailIgnoreCase(dto.email())) {
            throw new DuplicateEmailException("El email ya está registrado");
        }
        User user = User.builder()
                .email(dto.email().toLowerCase(Locale.ROOT))
                .passwordHash(passwordEncoder.encode(dto.password()))
                .name(dto.name())
                .gender(dto.gender())
                .build();
        return registerUser(user, version);
    }

    @Transactional
    public AuthResponseDTO loginOrRegisterGoogle(GoogleIdentity identity, GoogleAuthRequestDTO dto) {
        return existingGoogleSession(identity).orElseGet(() -> {
            String version = termsService.requireCurrentAcceptance(dto.termsAccepted(), dto.termsVersion());
            User user = User.builder()
                    .email(identity.email())
                    .googleSubject(identity.subject())
                    .name(identity.name())
                    .build();
            return registerUser(user, version);
        });
    }

    @Transactional
    public Optional<AuthResponseDTO> recoverGoogleRegistration(GoogleIdentity identity) {
        return existingGoogleSession(identity);
    }

    private Optional<AuthResponseDTO> existingGoogleSession(GoogleIdentity identity) {
        Optional<User> existing = userRepository.findByGoogleSubject(identity.subject());
        if (existing.isPresent()) {
            return existing.map(this::issueTokens);
        }
        Optional<User> matchingEmail = userRepository.findByEmailIgnoreCase(identity.email());
        if (matchingEmail.isPresent()) {
            User user = matchingEmail.get();
            // Otra transacción puede haber creado la cuenta entre ambas consultas.
            if (identity.subject().equals(user.getGoogleSubject())) {
                return Optional.of(issueTokens(user));
            }
            String message = user.hasPasswordCredential()
                    ? "El correo ya está registrado. Ingresa con tu correo y contraseña."
                    : "El correo ya se usa en Yuyay con otra cuenta de Google.";
            throw new AuthFlowException(HttpStatus.CONFLICT, "GOOGLE_EMAIL_CONFLICT", message);
        }
        return Optional.empty();
    }

    private AuthResponseDTO registerUser(User user, String version) {
        user.setRole(Role.USER);
        user.setTermsVersion(version);
        user.setTermsAcceptedAt(clock.instant());
        userRepository.save(user);
        eventPublisher.publishEvent(new UserRegisteredEvent(user.getId()));
        return issueTokens(user);
    }

    @Transactional
    public AuthResponseDTO login(LoginRequestDTO dto) {
        User user = userRepository.findByEmailIgnoreCase(dto.email())
                .orElseThrow(() -> new InvalidCredentialsException("Email o contraseña incorrectos"));
        if (!user.hasPasswordCredential() || dto.password().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(dto.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Email o contraseña incorrectos");
        }
        return issueTokens(user);
    }

    @Transactional
    public AuthResponseDTO refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawRefreshToken))
                .orElseThrow(() -> new InvalidTokenException("Refresh token inválido"));
        if (!stored.isActive()) {
            throw new InvalidTokenException("Refresh token expirado o revocado");
        }
        stored.setRevokedAt(Instant.now());
        return issueTokens(stored.getUser());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256(rawRefreshToken))
                .ifPresent(t -> t.setRevokedAt(Instant.now()));
    }

    private AuthResponseDTO issueTokens(User user) {
        String access = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name());
        String rawRefresh = TokenHasher.randomToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(TokenHasher.sha256(rawRefresh))
                .expiresAt(Instant.now().plus(Duration.ofDays(properties.jwt().refreshExpirationDays())))
                .build());
        return new AuthResponseDTO(access, rawRefresh, "Bearer", jwtService.accessTtlSeconds(), userMapper.toResponse(user));
    }
}
