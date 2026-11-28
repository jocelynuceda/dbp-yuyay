package com.yuyay.auth.service;

import com.yuyay.auth.dto.AuthResponseDTO;
import com.yuyay.auth.dto.GoogleAuthRequestDTO;
import com.yuyay.auth.dto.LoginRequestDTO;
import com.yuyay.auth.dto.RegisterRequestDTO;
import com.yuyay.auth.service.GoogleIdTokenVerifier.GoogleIdentity;
import com.yuyay.config.AppProperties;
import com.yuyay.event.UserRegisteredEvent;
import com.yuyay.identity.IdentityDirectory;
import com.yuyay.identity.IdentityLookup;
import com.yuyay.identity.IdentityRecord;
import com.yuyay.exception.AuthFlowException;
import com.yuyay.exception.DuplicateEmailException;
import com.yuyay.exception.FieldRejectedException;
import com.yuyay.exception.GoogleRegistrationRequiredException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private static final ZoneId PERU = ZoneId.of("America/Lima");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final AppProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final TermsService termsService;
    private final Clock clock;
    private final IdentityDirectory identityDirectory;

    private final RegistrationDniQuota dniQuota;
    private final TransactionTemplate transactions;

    @Value("${app.registration.min-age:16}")
    private int minimumAge;

    public AuthResponseDTO register(RegisterRequestDTO dto, String origin) {
        String version = termsService.requireCurrentAcceptance(dto.termsAccepted(), dto.termsVersion());
        if (userRepository.existsByEmailIgnoreCase(dto.email())) {
            throw new DuplicateEmailException("El email ya está registrado");
        }
        if (dto.dni() == null) {
            throw new FieldRejectedException("dni", "Ingresa tu DNI. Si no ves el campo, actualiza Yuyay.");
        }
        if (dto.fullName() == null || dto.fullName().isBlank()) {
            throw new FieldRejectedException("fullName", "Escribe tus nombres y apellidos. Si no ves el campo, actualiza Yuyay.");
        }
        // Después del email duplicado: la consulta de DNI se paga o consume cuota.
        LocalDate birthDate = requireDniMatch(dto.dni(), dto.fullName(), origin);
        User user = User.builder()
                .email(dto.email().toLowerCase(Locale.ROOT))
                .passwordHash(passwordEncoder.encode(dto.password()))
                .name(dto.name())
                .fullName(dto.fullName().strip())
                .birthDate(birthDate)
                .gender(dto.gender())
                .build();
        try {
            return transactions.execute(status -> registerUser(user, version));
        } catch (DataIntegrityViolationException e) {
            if (userRepository.existsByEmailIgnoreCase(dto.email())) {
                throw new DuplicateEmailException("El email ya está registrado");
            }
            throw e;
        }
    }

    /** Devuelve la fecha de nacimiento del DNI, ya comprobada la edad mínima. */
    private LocalDate requireDniMatch(String dni, String declaredName, String origin) {
        if (!identityDirectory.isConfigured()) {
            throw directoryUnavailable();
        }
        dniQuota.reserve(origin);
        IdentityRecord person = switch (identityDirectory.findByDni(dni)) {
            case IdentityLookup.Found found -> found.person();
            case IdentityLookup.NotFound ignored -> null;
            case IdentityLookup.Unavailable ignored -> throw directoryUnavailable();
        };
        if (person == null || !person.matchesName(declaredName)) {
            throw new FieldRejectedException("dni", "Revisa el DNI y escribe tus nombres y apellidos tal como figuran en tu documento.");
        }
        // Después del nombre: así no se averigua la edad de alguien conociendo solo su DNI.
        requireMinimumAge(person.birthDate());
        return person.birthDate();
    }

    private void requireMinimumAge(LocalDate birthDate) {
        if (birthDate == null) {
            throw directoryUnavailable();
        }
        if (Period.between(birthDate, LocalDate.ofInstant(clock.instant(), PERU)).getYears() < minimumAge) {
            throw new FieldRejectedException("dni", "Debes tener " + minimumAge + " años o más para crear una cuenta.");
        }
    }

    private AuthFlowException directoryUnavailable() {
        return new AuthFlowException(HttpStatus.SERVICE_UNAVAILABLE, "DNI_LOOKUP_UNAVAILABLE",
                "No se pudo consultar el DNI en este momento. Inténtalo más tarde.");
    }

    public AuthResponseDTO loginOrRegisterGoogle(GoogleIdentity identity, GoogleAuthRequestDTO dto, String origin) {
        String acceptedVersion = termsService.parseGoogleAcceptedVersion(dto.acceptedTermsVersion());
        Optional<AuthResponseDTO> session = transactions.execute(status -> existingGoogleSession(identity));
        if (session.isPresent()) {
            return session.get();
        }
        String version = termsService.requireCurrentGoogleAcceptance(acceptedVersion);
        if (dto.dni() == null || dto.dni().isBlank() || dto.fullName() == null || dto.fullName().isBlank()
                || dto.name() == null || dto.name().isBlank()) {
            throw new GoogleRegistrationRequiredException("REGISTRATION_DETAILS_REQUIRED",
                    "Para crear tu cuenta, escribe tu DNI, tus nombres y apellidos y tu nombre de usuario. "
                            + "Si no ves estos campos, actualiza Yuyay.", version);
        }
        if (!dto.dni().matches("[0-9]{8}")) {
            throw new FieldRejectedException("dni", "El DNI debe tener ocho dígitos");
        }
        if (dto.name().length() > 120) {
            throw new FieldRejectedException("name", "El nombre de usuario admite hasta 120 caracteres");
        }
        if (dto.fullName().length() > 200) {
            throw new FieldRejectedException("fullName", "Los nombres y apellidos admiten hasta 200 caracteres");
        }
        LocalDate birthDate = requireDniMatch(dto.dni(), dto.fullName(), origin);
        User user = User.builder()
                .email(identity.email())
                .birthDate(birthDate)
                .googleSubject(identity.subject())
                .name(dto.name().strip())
                .fullName(dto.fullName().strip())
                .build();
        // Otra solicitud puede haber creado la cuenta durante la consulta al directorio.
        return transactions.execute(status -> existingGoogleSession(identity).orElseGet(() -> registerUser(user, version)));
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
