package com.yuyay.auth.controller;

import com.yuyay.auth.dto.AuthResponseDTO;
import com.yuyay.auth.dto.GoogleAuthRequestDTO;
import com.yuyay.auth.dto.LoginRequestDTO;
import com.yuyay.auth.dto.RefreshTokenRequestDTO;
import com.yuyay.auth.dto.RegisterRequestDTO;
import com.yuyay.auth.dto.TermsResponseDTO;
import com.yuyay.auth.service.AuthService;
import com.yuyay.auth.service.GoogleAuthService;
import com.yuyay.auth.service.TermsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponseDTO register(@Valid @RequestBody RegisterRequestDTO dto) {
        return authService.register(dto);
    }

    @PostMapping("/login")
    public AuthResponseDTO login(@Valid @RequestBody LoginRequestDTO dto) {
        return authService.login(dto);
    }

    @PostMapping("/google")
    public AuthResponseDTO google(@Valid @RequestBody GoogleAuthRequestDTO dto) {
        return googleAuthService.signIn(dto);
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
}
