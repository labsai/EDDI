package ai.labs.eddi.integrations.slack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Verifies Slack request signatures using HMAC-SHA256 as specified in
 * <a href="https://api.slack.com/authentication/verifying-requests-from-slack">
 * Slack's request verification docs</a>.
 * <p>
 * Verification steps:
 * <ol>
 * <li>Check timestamp is within 5 minutes (replay protection)</li>
 * <li>Compute {@code v0=sha256(signingSecret:timestamp:body)}</li>
 * <li>Compare against {@code X-Slack-Signature} header (constant-time)</li>
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

    private final SlackIntegrationConfig config;

    @Inject
    public SlackSignatureVerifier(SlackIntegrationConfig config) {
        this.config = config;

        // Startup validation: warn loudly if enabled but secrets are missing
        if (config.enabled()) {
            if (config.signingSecret().isEmpty() || config.signingSecret().get().isBlank()) {
                LOGGER.error("Slack integration is ENABLED but eddi.slack.signing-secret is NOT SET. "
                        + "All incoming events will be rejected!");
            }
        }
    }

    /**
     * Verify that the request came from Slack.
     *
     * @param timestamp
     *            the {@code X-Slack-Request-Timestamp} header value
     * @param rawBody
     *            the raw request body (UTF-8 string)
     * @param signature
     *            the {@code X-Slack-Signature} header value
     * @return true if the signature is valid and the timestamp is fresh
     */
    public boolean verify(String timestamp, String rawBody, String signature) {
        if (timestamp == null || rawBody == null || signature == null) {
            LOGGER.warn("Slack signature verification: missing required headers");
            return false;
        }

        // Replay protection: reject requests older than 5 minutes
        try {
            long requestTs = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - requestTs) > MAX_TIMESTAMP_AGE_SECONDS) {
                LOGGER.warnf("Slack request timestamp too old: %d (now: %d, delta: %ds)",
                        requestTs, now, Math.abs(now - requestTs));
                return false;
            }
        } catch (NumberFormatException e) {
            LOGGER.warnf("Invalid Slack timestamp: %s", timestamp);
            return false;
        }

        // Compute expected signature
        String signingSecret = config.signingSecret().orElse("");
        String baseString = VERSION + ":" + timestamp + ":" + rawBody;

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] hash = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));

            String computed = VERSION + "=" + bytesToHex(hash);

            // Constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            LOGGER.error("Slack signature computation failed", e);
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
