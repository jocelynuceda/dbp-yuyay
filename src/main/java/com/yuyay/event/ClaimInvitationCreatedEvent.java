package com.yuyay.event;

/**
 * El enlace de entrega viaja aquí porque solo existe en claro en el momento de emitirlo: en la base
 * vive su hash. Que llegue por correo es lo que acredita el buzón invitado.
 */
public record ClaimInvitationCreatedEvent(Long invitationId, String claimLink) {}
