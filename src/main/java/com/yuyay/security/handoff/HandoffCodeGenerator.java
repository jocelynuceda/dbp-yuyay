package com.yuyay.security.handoff;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

/** TOTP HMAC-SHA256 de seis dígitos; sólo se verifica el paso actual de treinta segundos. */
@Component
public class HandoffCodeGenerator {
    public static final int PERIOD_SECONDS = 30;
    private final SecureRandom random = new SecureRandom();

    public byte[] newSecret() {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return secret;
    }

    public String code(byte[] secret, Instant now) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES)
                    .putLong(step(now)).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int value = ByteBuffer.wrap(digest, offset, Integer.BYTES).getInt() & 0x7fffffff;
            return String.format(Locale.ROOT, "%06d", value % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo generar el código de acceso");
        }
    }

    public boolean matches(byte[] secret, String submitted, Instant now) {
        return submitted != null && submitted.matches("[0-9]{6}")
                && MessageDigest.isEqual(code(secret, now).getBytes(StandardCharsets.US_ASCII),
                submitted.getBytes(StandardCharsets.US_ASCII));
    }

    public Instant periodExpiresAt(Instant now) {
        return Instant.ofEpochSecond((step(now) + 1) * PERIOD_SECONDS);
    }

    public long step(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), PERIOD_SECONDS);
    }
}
