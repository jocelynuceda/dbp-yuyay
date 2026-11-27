package com.yuyay.auth.controller;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.yuyay.auth.dto.AuthResponseDTO;
import com.yuyay.auth.dto.DniLookupRequestDTO;
import com.yuyay.auth.dto.DniLookupResponseDTO;
import com.yuyay.auth.dto.GoogleAuthRequestDTO;
import com.yuyay.auth.dto.LoginRequestDTO;
import com.yuyay.auth.dto.RefreshTokenRequestDTO;
import com.yuyay.auth.dto.RegisterRequestDTO;
import com.yuyay.auth.dto.TermsResponseDTO;
import com.yuyay.auth.service.AuthService;
import com.yuyay.auth.service.GoogleAuthService;
import com.yuyay.auth.service.TermsService;
import com.yuyay.exception.ErrorResponseDTO;
import com.yuyay.exception.GoogleRegistrationRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final GoogleAuthService googleAuthService;
    private final TermsService termsService;

    @GetMapping("/terms")
    public ResponseEntity<TermsResponseDTO> terms() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(termsService.current());
    }

    /**
     * Nombre que figura en el DNI, para que la persona confirme que es ella antes de crear la cuenta.
     * Es anónima y revela un dato personal: la protege el mismo cupo diario que el registro.
     */
    @PostMapping("/dni-lookup")
    public ResponseEntity<DniLookupResponseDTO> dniLookup(
            @Valid @RequestBody DniLookupRequestDTO dto, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new DniLookupResponseDTO(authService.lookUpFullName(dto.dni(), request.getRemoteAddr())));
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponseDTO register(@Valid @RequestBody RegisterRequestDTO dto, HttpServletRequest request) {
        return authService.register(dto, request.getRemoteAddr());
    }

    @PostMapping("/login")
    public AuthResponseDTO login(@Valid @RequestBody LoginRequestDTO dto) {
        return authService.login(dto);
    }

    @PostMapping("/google")
    public AuthResponseDTO google(@Valid @RequestBody GoogleAuthRequestDTO dto, HttpServletRequest request) {
        return googleAuthService.signIn(dto, request.getRemoteAddr());
    }

    @ExceptionHandler(GoogleRegistrationRequiredException.class)
    public ResponseEntity<GoogleRegistrationError> googleRegistrationRequired(
            GoogleRegistrationRequiredException ex, HttpServletRequest request) {
        HttpStatus status = ex.getStatus();
        ErrorResponseDTO error = new ErrorResponseDTO(Instant.now(), status.value(), status.getReasonPhrase(),
                ex.getMessage(), request.getRequestURI(), null, ex.getCode());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new GoogleRegistrationError(error, ex.getCurrentVersion()));
    }

    @PostMapping("/refresh")
    public AuthResponseDTO refresh(@Valid @RequestBody RefreshTokenRequestDTO dto) {
        return authService.refresh(dto.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequestDTO dto) {
        authService.logout(dto.refreshToken());
    }

    public record GoogleRegistrationError(@JsonUnwrapped ErrorResponseDTO error, String currentVersion) {}
}
