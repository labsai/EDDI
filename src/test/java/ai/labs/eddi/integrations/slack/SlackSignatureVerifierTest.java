/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SlackSignatureVerifierTest {

    private SlackSignatureVerifier verifier;

    private static final String SIGNING_SECRET = "test_fake_secret_00000000000000a1";
    private static final String SIGNING_SECRET_2 = "test_fake_secret_00000000000000b2";
    private static final String TEST_BODY = "token=test_fake_token_000&team_id=T0000TEST&team_domain=testteamnow";

    @BeforeEach
    void setUp() {
        verifier = new SlackSignatureVerifier();
    }

    @Test
    void verify_validSignature_returnsTrue() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String baseString = "v0:" + timestamp + ":" + TEST_BODY;
        String expectedSignature = computeHmac(SIGNING_SECRET, baseString);

        assertTrue(verifier.verify(timestamp, TEST_BODY, expectedSignature, Set.of(SIGNING_SECRET)));
    }

    @Test
    void verify_validSignature_multipleSecrets_returnsTrue() {
        // Correct secret is the second one in the set
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String baseString = "v0:" + timestamp + ":" + TEST_BODY;
        String expectedSignature = computeHmac(SIGNING_SECRET, baseString);

        assertTrue(verifier.verify(timestamp, TEST_BODY, expectedSignature,
                List.of(SIGNING_SECRET_2, SIGNING_SECRET)));
    }

    @Test
    void verify_noMatchingSecret_returnsFalse() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String baseString = "v0:" + timestamp + ":" + TEST_BODY;
        String expectedSignature = computeHmac(SIGNING_SECRET, baseString);

        // Only wrong secrets provided
        assertFalse(verifier.verify(timestamp, TEST_BODY, expectedSignature,
                Set.of(SIGNING_SECRET_2, "totally-wrong-secret")));
    }

    @Test
    void verify_invalidSignature_returnsFalse() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        assertFalse(verifier.verify(timestamp, TEST_BODY, "v0=invalid_signature",
                Set.of(SIGNING_SECRET)));
    }

    @Test
    void verify_expiredTimestamp_returnsFalse() {
        // 10 minutes ago — beyond the 5-minute window
        String expiredTimestamp = String.valueOf(Instant.now().getEpochSecond() - 600);

        String baseString = "v0:" + expiredTimestamp + ":" + TEST_BODY;
        String validSignature = computeHmac(SIGNING_SECRET, baseString);

        assertFalse(verifier.verify(expiredTimestamp, TEST_BODY, validSignature,
                Set.of(SIGNING_SECRET)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void verify_nullOrEmptyTimestamp_returnsFalse(String timestamp) {
        assertFalse(verifier.verify(timestamp, TEST_BODY, "v0=anything",
                Set.of(SIGNING_SECRET)));
    }

    @Test
    void verify_nullBody_returnsFalse() {
        assertFalse(verifier.verify("12345", null, "v0=anything",
                Set.of(SIGNING_SECRET)));
    }

    @Test
    void verify_nullSignature_returnsFalse() {
        assertFalse(verifier.verify("12345", TEST_BODY, null,
                Set.of(SIGNING_SECRET)));
    }

    @Test
    void verify_emptySecrets_returnsFalse() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        assertFalse(verifier.verify(timestamp, TEST_BODY, "v0=anything", Set.of()));
    }

    @Test
    void verify_nullSecrets_returnsFalse() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        assertFalse(verifier.verify(timestamp, TEST_BODY, "v0=anything", null));
    }

    @Test
    void verify_invalidTimestampFormat_returnsFalse() {
        assertFalse(verifier.verify("not-a-number", TEST_BODY, "v0=anything",
                Set.of(SIGNING_SECRET)));
    }

    @Test
    void verify_futureTimestamp_withinWindow_succeeds() {
        // 2 minutes in the future — within the 5-minute abs window
        String futureTs = String.valueOf(Instant.now().getEpochSecond() + 120);
        String baseString = "v0:" + futureTs + ":" + TEST_BODY;
        String signature = computeHmac(SIGNING_SECRET, baseString);

        assertTrue(verifier.verify(futureTs, TEST_BODY, signature,
                Set.of(SIGNING_SECRET)));
    }

    /**
     * Helper to compute HMAC-SHA256 signature in the v0= format.
     */
    private static String computeHmac(String secret, String baseString) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(baseString.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            var sb = new StringBuilder("v0=");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
