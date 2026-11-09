package com.yuyay.auth.service;

import com.yuyay.auth.dto.ResetPasswordRequestDTO;
import com.yuyay.event.PasswordResetRequestedEvent;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.security.TokenHasher;
import com.yuyay.user.entity.PasswordResetCode;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.PasswordResetCodeRepository;
import com.yuyay.user.repository.RefreshTokenRepository;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PasswordResetService {
    private static final Duration CODE_TTL = Duration.ofMinutes(15);
    private static final Duration RESEND_WAIT = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository codeRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    /** Responde igual exista o no la cuenta, para no revelar qué correos están registrados. */
    @Transactional
    public void requestCode(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(this::issueCode);
    }

    // Sin noRollbackFor, el intento fallido se perdería al lanzar la excepción
    @Transactional(noRollbackFor = InvalidOperationException.class)
    public void resetPassword(ResetPasswordRequestDTO dto) {
        if (dto.newPassword().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidOperationException("La contraseña es demasiado larga");
        }
        User user = userRepository.findByEmailIgnoreCase(dto.email()).orElseThrow(this::invalidCode);
        PasswordResetCode stored = codeRepository.findByUserId(user.getId()).orElseThrow(this::invalidCode);
        if (stored.getExpiresAt().isBefore(Instant.now()) || stored.getAttempts() >= MAX_ATTEMPTS) {
            throw invalidCode();
        }
        if (!matches(stored.getCodeHash(), hash(user, dto.code()))) {
            stored.setAttempts(stored.getAttempts() + 1);
            throw invalidCode();
        }
        user.setPasswordHash(passwordEncoder.encode(dto.newPassword()));
        codeRepository.delete(stored);
        // Quien tenía la sesión abierta con la contraseña anterior deja de poder renovarla
        refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
    }

    private void issueCode(User user) {
        Instant now = Instant.now();
        PasswordResetCode entry = codeRepository.findByUserId(user.getId())
                .orElseGet(() -> PasswordResetCode.builder().user(user).build());
        if (entry.getSentAt() != null && entry.getSentAt().plus(RESEND_WAIT).isAfter(now)) {
            return;
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        entry.setCodeHash(hash(user, code));
        entry.setExpiresAt(now.plus(CODE_TTL));
        entry.setSentAt(now);
        entry.setAttempts(0);
        codeRepository.save(entry);
        eventPublisher.publishEvent(
                new PasswordResetRequestedEvent(user.getEmail(), user.getName(), code, CODE_TTL.toMinutes()));
    }

    private String hash(User user, String code) {
        return TokenHasher.sha256(user.getId() + ":" + code);
    }

    private boolean matches(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private InvalidOperationException invalidCode() {
        return new InvalidOperationException("El código es incorrecto o ya venció");
    }
}
