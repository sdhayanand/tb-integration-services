package com.tailoredbrands.otd.shipmentwebhook.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifies {@code X-Carrier-Signature}: lower-case hex HMAC-SHA256 of the raw request body with the
 * shared secret; an optional {@code sha256=} prefix (GitHub/Stripe style) is accepted. Comparison is
 * constant-time.
 */
public class HmacSignatureVerifier {

    public static final String HEADER = "X-Carrier-Signature";
    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final byte[] secret;
    private final boolean skipVerification;

    public HmacSignatureVerifier(String secret, boolean skipVerification) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.skipVerification = skipVerification;
    }

    public boolean skipsVerification() {
        return skipVerification;
    }

    /** @return {@code true} when the signature matches (or verification is disabled). */
    public boolean verify(byte[] body, String signatureHeader) {
        if (skipVerification) {
            return true;
        }
        if (secret.length == 0 || signatureHeader == null || signatureHeader.isBlank() || body == null) {
            return false;
        }
        String provided = signatureHeader.trim().toLowerCase(Locale.ROOT);
        if (provided.startsWith(PREFIX)) {
            provided = provided.substring(PREFIX.length());
        }
        byte[] providedBytes;
        try {
            providedBytes = HexFormat.of().parseHex(provided);
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        byte[] expected = sign(body);
        return MessageDigest.isEqual(expected, providedBytes);
    }

    /** Computes the signature (used by tests and by the README's curl examples). */
    public byte[] sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public String signHex(byte[] body) {
        return HexFormat.of().formatHex(sign(body));
    }
}
