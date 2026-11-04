package com.yuyay.attachment.dto;

import java.time.Instant;

/** Credencial temporal: no persistir, registrar ni almacenar en caché. */
public record AttachmentAccessResponse(String url, Instant expiresAt) {}
