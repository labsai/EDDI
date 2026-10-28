/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook;

import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.integrations.channels.HmacSignatures;
import ai.labs.eddi.integrations.webhook.WebhookSignatureVerifier.Result;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "test-key-test-key";
    private static final long NOW = 1_760_000_000L;
    private static final byte[] BODY = "{\"action\":\"opened\",\"number\":7}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    private static WebhookChannelSettings settings(Map<String, String> extra) {
        Map<String, String> pc = new HashMap<>(extra);
        WebhookChannelSettings parsed = WebhookChannelSettings.from(pc);
        // The router hands the endpoint RESOLVED secrets; simulate that.
        return new WebhookChannelSettings(SECRET, parsed.scheme(), parsed.signatureHeader(), parsed.signatureEncoding(),
                parsed.signaturePrefix(), parsed.eventTypeHeader(), parsed.eventTypePath(), parsed.eventIdHeader(),
                parsed.inputTemplate(), parsed.conversationStrategy(), parsed.correlationKeyPath());
    }

    private static Result verify(WebhookSignatureVerifier verifier, WebhookChannelSettings settings, byte[] body,
                                 Map<String, String> headers) {
        Map<String, String> lower = new HashMap<>();
        headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        return verifier.verify(settings, body, name -> lower.get(name.toLowerCase(Locale.ROOT)));
    }

    static String eddiHeader(String secret, long t, byte[] body) {
        return "t=" + t + ",v1=" + HmacSignatures.hmacSha256Hex(secret, (t + ".").getBytes(StandardCharsets.UTF_8), body);
    }

    @Nested
    class EddiV1 {
        private final WebhookChannelSettings settings = settings(Map.of());

        @Test
        void valid() {
            assertEquals(Result.VALID, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", eddiHeader(SECRET, NOW, BODY))));
        }

        @Test
        void replayKeyIsCanonicalAcrossHeaderVariations() {
            String header = eddiHeader(SECRET, NOW, BODY);
            var plain = verifier.verifyWithReplayKey(settings, BODY, name -> "X-Eddi-Signature".equalsIgnoreCase(name) ? header : null);
            String padded = header + ",v1=" + "00".repeat(32);
            var varied = verifier.verifyWithReplayKey(settings, BODY, name -> "X-Eddi-Signature".equalsIgnoreCase(name) ? padded : null);
            assertEquals(Result.VALID, varied.result());
            assertEquals(plain.replayKey(), varied.replayKey());
            // A different timestamp is a different signed message, hence a different key.
            String later = eddiHeader(SECRET, NOW + 1, BODY);
            var other = verifier.verifyWithReplayKey(settings, BODY, name -> "X-Eddi-Signature".equalsIgnoreCase(name) ? later : null);
            assertNotEquals(plain.replayKey(), other.replayKey());
            // Never a key for a refused request.
            assertNull(verifier.verifyWithReplayKey(settings, BODY, name -> null).replayKey());
        }

        @Test
        void rotationAllowsSeveralV1Entries() {
            String header = eddiHeader(SECRET, NOW, BODY);
            String rotated = "t=" + NOW + ",v1=" + "00".repeat(32) + "," + header.substring(header.indexOf("v1="));
            assertEquals(Result.VALID, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", rotated)));
        }

        @Test
        void wrongSecret() {
            assertEquals(Result.INVALID_SIGNATURE,
                    verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", eddiHeader("other-secret", NOW, BODY))));
        }

        @Test
        void staleTimestamp() {
            long old = NOW - WebhookSignatureVerifier.MAX_TIMESTAMP_AGE_SECONDS - 1;
            assertEquals(Result.STALE_TIMESTAMP, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", eddiHeader(SECRET, old, BODY))));
        }

        @Test
        void futureTimestampBeyondWindowIsStale() {
            long ahead = NOW + WebhookSignatureVerifier.MAX_TIMESTAMP_AGE_SECONDS + 1;
            assertEquals(Result.STALE_TIMESTAMP,
                    verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", eddiHeader(SECRET, ahead, BODY))));
        }

        @Test
        void timestampThatOverflowsTheAgeComputationIsStale() {
            // NOW - (Long.MIN_VALUE + NOW) overflows to Long.MIN_VALUE, whose
            // Math.abs is negative — it used to pass the freshness check.
            long overflowing = Long.MIN_VALUE + NOW;
            assertEquals(Result.STALE_TIMESTAMP,
                    verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", eddiHeader(SECRET, overflowing, BODY))));
        }

        @Test
        void tamperedBody() {
            byte[] tampered = "{\"action\":\"closed\",\"number\":7}".getBytes(StandardCharsets.UTF_8);
            assertEquals(Result.INVALID_SIGNATURE,
                    verify(verifier, settings, tampered, Map.of("X-Eddi-Signature", eddiHeader(SECRET, NOW, BODY))));
        }

        @Test
        void timestampIsCoveredByTheMac() {
            String header = eddiHeader(SECRET, NOW, BODY).replace("t=" + NOW, "t=" + (NOW - 1));
            assertEquals(Result.INVALID_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", header)));
        }

        @Test
        void missingHeader() {
            assertEquals(Result.MISSING_SIGNATURE, verify(verifier, settings, BODY, Map.of()));
        }

        @Test
        void malformedHeader() {
            assertEquals(Result.MALFORMED_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", "v1=abc")));
            assertEquals(Result.MALFORMED_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", "t=abc,v1=abc")));
        }

        @Test
        void secondTimestampEntryIsMalformed() {
            // t=X,t=T,v1=<valid MAC over T>: verification read T while the replay
            // key read X, so every X minted a fresh dedup key for one signed request.
            String header = eddiHeader(SECRET, NOW, BODY);
            assertEquals(Result.MALFORMED_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", "t=1," + header)));
            assertEquals(Result.MALFORMED_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", header + ",t=1")));
        }

        @Test
        void unresolvedSecretFailsClosed() {
            // A record built directly keeps the whitespace WebhookChannelSettings.from()
            // would trim.
            var padded = new WebhookChannelSettings(" ${vault:a-reference-that-is-long-enough}", WebhookChannelSettings.Scheme.EDDI_V1,
                    "X-Signature", "hex", "", null, null, "X-Event-Id", null, WebhookChannelSettings.Strategy.NEW, null);
            assertEquals(Result.NO_SECRET, verify(verifier, padded, BODY, Map.of("X-Eddi-Signature",
                    eddiHeader(" ${vault:a-reference-that-is-long-enough}", NOW, BODY))));
            var unresolved = WebhookChannelSettings.from(Map.of("signingSecret", "${vault:missing}"));
            assertEquals(Result.NO_SECRET, verify(verifier, unresolved, BODY, Map.of("X-Eddi-Signature", eddiHeader("${vault:missing}", NOW, BODY))));
            var none = WebhookChannelSettings.from(Map.of());
            assertEquals(Result.NO_SECRET, verify(verifier, none, BODY, Map.of("X-Eddi-Signature", eddiHeader(SECRET, NOW, BODY))));
        }
    }

    @Nested
    class GitHub {
        private final WebhookChannelSettings settings = settings(Map.of("signatureScheme", "github"));

        private String header(String secret, byte[] body) {
            return "sha256=" + HmacSignatures.hmacSha256Hex(secret, body);
        }

        @Test
        void valid() {
            assertEquals(Result.VALID, verify(verifier, settings, BODY, Map.of("X-Hub-Signature-256", header(SECRET, BODY))));
        }

        @Test
        void wrongSecret() {
            assertEquals(Result.INVALID_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Hub-Signature-256", header("nope", BODY))));
        }

        @Test
        void tamperedBody() {
            assertEquals(Result.INVALID_SIGNATURE,
                    verify(verifier, settings, "{}".getBytes(StandardCharsets.UTF_8), Map.of("X-Hub-Signature-256", header(SECRET, BODY))));
        }

        @Test
        void missingOrUnprefixedHeader() {
            assertEquals(Result.MISSING_SIGNATURE, verify(verifier, settings, BODY, Map.of()));
            assertEquals(Result.MALFORMED_SIGNATURE,
                    verify(verifier, settings, BODY, Map.of("X-Hub-Signature-256", HmacSignatures.hmacSha256Hex(SECRET, BODY))));
        }

        @Test
        void eddiHeaderDoesNotSatisfyGithubScheme() {
            assertEquals(Result.MISSING_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Eddi-Signature", eddiHeader(SECRET, NOW, BODY))));
        }
    }

    @Nested
    class GenericHmac {

        @Test
        void hexInCustomHeader() {
            var settings = settings(Map.of("signatureScheme", "generic-hmac", "signatureHeader", "X-Acme-Signature"));
            assertEquals(Result.VALID,
                    verify(verifier, settings, BODY, Map.of("X-Acme-Signature", HmacSignatures.hmacSha256Hex(SECRET, BODY))));
            assertEquals(Result.MISSING_SIGNATURE,
                    verify(verifier, settings, BODY, Map.of("X-Signature", HmacSignatures.hmacSha256Hex(SECRET, BODY))));
        }

        @Test
        void base64WithPrefix() {
            var settings = settings(Map.of("signatureScheme", "generic-hmac", "signatureEncoding", "base64", "signaturePrefix", "sha256="));
            String good = "sha256=" + Base64.getEncoder().encodeToString(HmacSignatures.hmacSha256(SECRET, BODY));
            String bad = "sha256=" + Base64.getEncoder().encodeToString(HmacSignatures.hmacSha256("x", BODY));
            assertEquals(Result.VALID, verify(verifier, settings, BODY, Map.of("X-Signature", good)));
            assertEquals(Result.INVALID_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Signature", bad)));
            assertEquals(Result.MALFORMED_SIGNATURE, verify(verifier, settings, BODY, Map.of("X-Signature", "sha256=%%%")));
        }

        @Test
        void tamperedBody() {
            var settings = settings(Map.of("signatureScheme", "generic-hmac"));
            assertEquals(Result.INVALID_SIGNATURE, verify(verifier, settings, "{\"x\":1}".getBytes(StandardCharsets.UTF_8),
                    Map.of("X-Signature", HmacSignatures.hmacSha256Hex(SECRET, BODY))));
        }
    }
}
