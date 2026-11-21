package com.yuyay.auth.service;

import com.yuyay.auth.dto.AuthResponseDTO;
import com.yuyay.auth.dto.GoogleAuthRequestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleAuthService {
    private final GoogleIdTokenVerifier verifier;
    private final AuthService authService;

    public AuthResponseDTO signIn(GoogleAuthRequestDTO dto) {
        var identity = verifier.verify(dto.idToken());
        try {
            return authService.loginOrRegisterGoogle(identity, dto);
        } catch (DataIntegrityViolationException ex) {
            // El índice único arbitra el alta entre instancias. La lectura se hace tras el rollback.
            return authService.recoverGoogleRegistration(identity).orElseThrow(() -> ex);
        }
    }
}
