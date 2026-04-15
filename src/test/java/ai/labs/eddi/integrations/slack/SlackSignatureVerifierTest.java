package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlackSignatureVerifierTest {

    private SlackSignatureVerifier verifier;
    private SlackIntegrationConfig config;

    private static final String SIGNING_SECRET = "8f742231b10e8888abcd99yez56789d0";
    private static final String TEST_BODY = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow";

    @BeforeEach
    void setUp() {
        config = mock(SlackIntegrationConfig.class);
        when(config.signingSecret()).thenReturn(java.util.Optional.of(SIGNING_SECRET));
        verifier = new SlackSignatureVerifier(config);
    }

    @Test
    void verify_validSignature_returnsTrue() {
        // Use a fresh timestamp
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        // Compute the expected signature manually
        String baseString = "v0:" + timestamp + ":" + TEST_BODY;
        String expectedSignature = computeHmac(SIGNING_SECRET, baseString);

        assertTrue(verifier.verify(timestamp, TEST_BODY, expectedSignature));
    }

    @Test
    void verify_invalidSignature_returnsFalse() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        assertFalse(verifier.verify(timestamp, TEST_BODY, "v0=invalid_signature"));
    }

    @Test
    void verify_expiredTimestamp_returnsFalse() {
        // 10 minutes ago — beyond the 5-minute window
        String expiredTimestamp = String.valueOf(Instant.now().getEpochSecond() - 600);

        String baseString = "v0:" + expiredTimestamp + ":" + TEST_BODY;
        String validSignature = computeHmac(SIGNING_SECRET, baseString);

        assertFalse(verifier.verify(expiredTimestamp, TEST_BODY, validSignature));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void verify_nullOrEmptyTimestamp_returnsFalse(String timestamp) {
        assertFalse(verifier.verify(timestamp, TEST_BODY, "v0=anything"));
    }

    @Test
    void verify_nullBody_returnsFalse() {
        assertFalse(verifier.verify("12345", null, "v0=anything"));
    }

    @Test
    void verify_nullSignature_returnsFalse() {
        assertFalse(verifier.verify("12345", TEST_BODY, null));
    }

    @Test
    void verify_invalidTimestampFormat_returnsFalse() {
        assertFalse(verifier.verify("not-a-number", TEST_BODY, "v0=anything"));
    }

    @Test
    void verify_futureTimestamp_withinWindow_succeeds() {
        // 2 minutes in the future — within the 5-minute abs window
        String futureTs = String.valueOf(Instant.now().getEpochSecond() + 120);
        String baseString = "v0:" + futureTs + ":" + TEST_BODY;
        String signature = computeHmac(SIGNING_SECRET, baseString);

        assertTrue(verifier.verify(futureTs, TEST_BODY, signature));
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
