package com.yuyay.care.controller;

import com.yuyay.care.dto.ClaimExchangeRequest;
import com.yuyay.care.dto.ClaimExchangeResponse;
import com.yuyay.care.service.ClaimInvitationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Canje de la entrega. Requiere sesión: quien recibe su perfil ya tiene cuenta, y el canje queda
 * atado a esa cuenta autenticada, no al correo que venga en el cuerpo.
 */
@RestController
@RequestMapping("/api/v1/claim-invitations")
@RequiredArgsConstructor
public class ClaimInvitationController {

    private final ClaimInvitationService service;

    @PostMapping("/exchange")
    public ClaimExchangeResponse exchange(@Valid @RequestBody ClaimExchangeRequest req) {
        return service.exchange(req);
    }
}
