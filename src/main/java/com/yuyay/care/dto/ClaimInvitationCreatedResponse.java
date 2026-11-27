package com.yuyay.care.dto;

/**
 * El token solo se devuelve aquí, al emitir: en la base vive su hash. Si se pierde el enlace, se
 * emite otro y el anterior deja de servir.
 */
public record ClaimInvitationCreatedResponse(
        ClaimInvitationResponse invitation,
        String token,
        String claimLink
) {}
