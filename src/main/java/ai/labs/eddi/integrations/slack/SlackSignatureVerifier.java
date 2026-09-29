/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collection;

/**
 * Verifies Slack request signatures using HMAC-SHA256 as specified in
 * <a href="https://api.slack.com/authentication/verifying-requests-from-slack">
 * Slack's request verification docs</a>.
 * <p>
 * Supports multiple signing secrets (one per configured workspace) by trying
 * each secret until a match is found. This enables multi-workspace Slack
 * integration without requiring {@code team_id} extraction before verification.
 * <p>
 * Verification steps:
 * <ol>
 * <li>Check timestamp is within 5 minutes (replay protection)</li>
 * <li>For each signing secret, compute
 * {@code v0=sha256(signingSecret:timestamp:body)}</li>
 * <li>Compare against {@code X-Slack-Signature} header (constant-time)</li>
 * <li>Return true on first match</li>
 * </ol>
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class SlackSignatureVerifier {

    private static final Logger LOGGER = Logger.getLogger(SlackSignatureVerifier.class);
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String VERSION = "v0";

    /** Maximum allowed age of a request timestamp (5 minutes). */
    private static final long MAX_TIMESTAMP_AGE_SECONDS = 5 * 60;

    /**
     * Verify that the request came from Slack using one of the provided signing
     * secrets.
     *
     * @param timestamp
     *            the {@code X-Slack-Request-Timestamp} header value
     * @param rawBody
     *            the raw request body (UTF-8 string)
     * @param signature
     *            the {@code X-Slack-Signature} header value
     * @param signingSecrets
     *            the set of signing secrets to try (from all configured agents)
     * @return true if the signature matches any of the provided secrets and the
     *         timestamp is fresh
     */
    public boolean verify(String timestamp, String rawBody, String signature,
                          Collection<String> signingSecrets) {
        if (timestamp == null || rawBody == null || signature == null) {
            LOGGER.warn("Slack signature verification: missing required headers");
            return false;
        }

        if (signingSecrets == null || signingSecrets.isEmpty()) {
            LOGGER.warn("Slack signature verification: no signing secrets configured");
            return false;
        }

        if (!isTimestampFresh(timestamp)) {
            return false;
        }

        // Try each signing secret — return true on first match
        for (String secret : signingSecrets) {
            if (matchesSecret(timestamp, rawBody, signature, secret)) {
                return true;
            }
        }

        LOGGER.debug("Slack signature verification failed: no matching signing secret");
        return false;
    }

    /**
     * Verify the request signature against exactly ONE signing secret — the secret
     * of the integration that owns the acted-on channel. Used by the interactivity
     * endpoint (HITL decisions), where accepting any secret from the pool would let
     * a holder of one integration's secret forge a decision on another
     * integration's paused conversation (cross-integration IDOR).
     *
     * @param signingSecret
     *            the single signing secret to verify against (the owning
     *            integration's); a {@code null}/blank secret always fails
     * @return true only if the signature matches THIS secret and the timestamp is
     *         fresh
     */
    public boolean verifyWithSecret(String timestamp, String rawBody, String signature,
                                    String signingSecret) {
        if (timestamp == null || rawBody == null || signature == null) {
            LOGGER.warn("Slack signature verification: missing required headers");
            return false;
        }
        if (signingSecret == null || signingSecret.isBlank()) {
            LOGGER.warn("Slack signature verification: no signing secret for the owning integration");
            return false;
        }
        if (!isTimestampFresh(timestamp)) {
            return false;
        }
        if (matchesSecret(timestamp, rawBody, signature, signingSecret)) {
            return true;
        }
        LOGGER.debug("Slack signature verification failed: signature does not match the owning integration's secret");
        return false;
    }

    /** Replay protection: reject requests older than 5 minutes. */
    private boolean isTimestampFresh(String timestamp) {
        try {
            long requestTs = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - requestTs) > MAX_TIMESTAMP_AGE_SECONDS) {
                LOGGER.warnf("Slack request timestamp too old: %d (now: %d, delta: %ds)",
                        requestTs, now, Math.abs(now - requestTs));
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            LOGGER.warnf("Invalid Slack timestamp: %s", sanitize(timestamp));
            return false;
        }
    }

    /** Constant-time HMAC comparison of the request against a single secret. */
    private boolean matchesSecret(String timestamp, String rawBody, String signature, String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        String baseString = VERSION + ":" + timestamp + ":" + rawBody;
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));

            String computed = VERSION + "=" + bytesToHex(hash);

            // Constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.debugf("Slack signature computation failed for a secret: %s", e.getMessage());
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
