package com.tailoredbrands.otd.shipmentwebhook.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignatureVerifierTest {

    private static final byte[] BODY = "{\"trackingNumber\":\"1Z1\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void acceptsCorrectHexSignatureWithOrWithoutPrefix() {
        HmacSignatureVerifier verifier = new HmacSignatureVerifier("s3cr3t", false);
        String hex = verifier.signHex(BODY);

        assertThat(verifier.verify(BODY, hex)).isTrue();
        assertThat(verifier.verify(BODY, "sha256=" + hex)).isTrue();
        assertThat(verifier.verify(BODY, hex.toUpperCase())).isTrue();
    }

    @Test
    void knownVectorMatches() {
        // HMAC-SHA256(key="key", "The quick brown fox jumps over the lazy dog") -- RFC-style test vector
        HmacSignatureVerifier verifier = new HmacSignatureVerifier("key", false);
        byte[] body = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.signHex(body)).isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void rejectsWrongMissingOrMalformedSignatures() {
        HmacSignatureVerifier verifier = new HmacSignatureVerifier("s3cr3t", false);
        String hex = new HmacSignatureVerifier("other", false).signHex(BODY);

        assertThat(verifier.verify(BODY, hex)).isFalse();
        assertThat(verifier.verify(BODY, null)).isFalse();
        assertThat(verifier.verify(BODY, "")).isFalse();
        assertThat(verifier.verify(BODY, "not-hex!")).isFalse();
        assertThat(verifier.verify("tampered".getBytes(StandardCharsets.UTF_8), verifier.signHex(BODY))).isFalse();
    }

    @Test
    void emptySecretRejectsEverythingUnlessSkipping() {
        assertThat(new HmacSignatureVerifier("", false).verify(BODY, "00")).isFalse();
        assertThat(new HmacSignatureVerifier("", true).verify(BODY, null)).isTrue();
    }
}
