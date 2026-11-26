package com.yuyay.security.handoff;

import java.time.Instant;

public record HandoffAccessCodeDTO(String code, boolean consumed, Instant expiresAt, Instant serverTime, int periodSeconds) {}
