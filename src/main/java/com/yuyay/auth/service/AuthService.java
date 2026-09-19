package com.yuyay.auth.service;

import com.yuyay.auth.dto.AuthResponseDTO;
import com.yuyay.auth.dto.LoginRequestDTO;
import com.yuyay.auth.dto.RegisterRequestDTO;
import com.yuyay.config.AppProperties;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final AppProperties properties;

    @Transactional
    public AuthResponseDTO register(RegisterRequestDTO dto) {
        if (userRepository.existsByEmailIgnoreCase(dto.email())) {
            throw new DuplicateEmailException("El email ya está registrado");
        }
        User user = User.builder()
                .email(dto.email().toLowerCase())
                .passwordHash(passwordEncoder.encode(dto.password()))
                .name(dto.name())
                .role(Role.USER)
                .build();
        userRepository.save(user);
        return issueTokens(user);
    }

    @Transactional
    public AuthResponseDTO login(LoginRequestDTO dto) {
        User user = userRepository.findByEmailIgnoreCase(dto.email())
                .orElseThrow(() -> new InvalidCredentialsException("Email o contraseña incorrectos"));
        if (!passwordEncoder.matches(dto.password(), user.getPasswordHash())) {
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
