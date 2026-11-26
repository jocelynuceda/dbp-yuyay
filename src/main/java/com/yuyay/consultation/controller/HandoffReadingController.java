package com.yuyay.consultation.controller;

import com.yuyay.consultation.dto.HandoffResumedViewDTO;
import com.yuyay.consultation.service.HandoffReadingService;
import com.yuyay.security.handoff.HandoffAccessSecurityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/public/handoff-sessions")
@RequiredArgsConstructor
public class HandoffReadingController {
    public static final String READING_COOKIE = "__Secure-Handoff-Reading";
    private final HandoffReadingService reading;
    private final Clock clock;

    /** Consent requires the explicit in-memory grant, never ambient cookie authority. */
    @PostMapping("/{token}/reading-cookie")
    public ResponseEntity<Void> consent(@PathVariable String token,
            @RequestHeader(name = HandoffAccessSecurityService.GRANT_HEADER, required = false) String explicitGrant) {
        Instant expiresAt = reading.consent(token, explicitGrant);
        long seconds = Math.max(0, Duration.between(clock.instant(), expiresAt).getSeconds());
        ResponseCookie cookie = ResponseCookie.from(READING_COOKIE, explicitGrant)
                .path("/api/v1/public/handoff-sessions/" + UriUtils.encodePathSegment(token, StandardCharsets.UTF_8) + "/")
                .httpOnly(true).secure(true).sameSite("Strict").maxAge(seconds).build();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    @GetMapping("/{token}/resume")
    public ResponseEntity<HandoffResumedViewDTO> resume(@PathVariable String token,
            @CookieValue(name = READING_COOKIE, required = false) String cookieGrant) {
        return reading.resume(token, cookieGrant)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .header("Referrer-Policy", "no-referrer").body(view))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                        .header("Referrer-Policy", "no-referrer").build());
    }
}
