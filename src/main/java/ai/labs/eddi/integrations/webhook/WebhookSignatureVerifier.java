/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook;

import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.integrations.channels.HmacSignatures;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Verifies the signature of an inbound webhook request against the ONE signing
 * secret of the integration it is addressed to. Fail-closed: anything that is
 * not a positive match is a refusal.
 * <p>
 * Every scheme MACs the raw request bytes exactly as received — never a
 * re-serialization — which is why the endpoint reads the body as bytes.
 * <ul>
 * <li><b>eddi-v1</b> — {@code X-Eddi-Signature: t=<unix>,v1=<hex>} where the
 * MAC covers {@code t + "." + body}. The timestamp must be within five minutes.
 * Several {@code v1=} entries are allowed (secret rotation); any match
 * passes.</li>
 * <li><b>github</b> — {@code X-Hub-Signature-256: sha256=<hex>} over the body.
 * GitHub signs no timestamp, so replay protection rests on the delivery-id
 * deduplication.</li>
 * <li><b>generic-hmac</b> — MAC over the body in a configurable header, hex or
 * base64, with an optional literal prefix. No timestamp either.</li>
 * </ul>
 *
 * @since 6.6.0
 */
@ApplicationScoped
public class WebhookSignatureVerifier {

    public static final String EDDI_SIGNATURE_HEADER = "X-Eddi-Signature";
    public static final String GITHUB_SIGNATURE_HEADER = "X-Hub-Signature-256";

    /** Freshness window of the eddi-v1 timestamp. */
    public static final long MAX_TIMESTAMP_AGE_SECONDS = 5 * 60;

    /** Outcome of a verification; only {@link #VALID} admits the request. */
    public enum Result {
        VALID, MISSING_SIGNATURE, MALFORMED_SIGNATURE, STALE_TIMESTAMP, INVALID_SIGNATURE, NO_SECRET
    }

    /**
     * A verification outcome and, when {@link Result#VALID}, the request's replay
     * key.
     *
     * @param replayKey
     *            a canonical identity of what was signed — the expected MAC, which
     *            no header variation (extra {@code v1=} candidates, hex case, a
     *            base64 re-encoding) can change; {@code null} unless VALID
     */
    public record Verification(Result result, String replayKey) {
    }

    private final Clock clock;

    public WebhookSignatureVerifier() {
        this(Clock.systemUTC());
    }

    WebhookSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param settings
     *            the integration's settings, with the signing secret RESOLVED
     * @param rawBody
     *            the request body exactly as received
     * @param headers
     *            header lookup by (case-insensitive) name; returns null when absent
     */
    public Result verify(WebhookChannelSettings settings, byte[] rawBody, UnaryOperator<String> headers) {
        return verifyWithReplayKey(settings, rawBody, headers).result();
    }

    /**
     * Like {@link #verify}, and on success also the canonical replay key the
     * endpoint deduplicates on. It is derived from the MAC EDDI computed, never
     * from the header the sender supplied: the header can be varied without
     * breaking the signature (an extra invalid {@code v1=} candidate, upper-case
     * hex), and a key taken from it would let a captured request be replayed under
     * a new key.
     */
    public Verification verifyWithReplayKey(WebhookChannelSettings settings, byte[] rawBody, UnaryOperator<String> headers) {
        Result result = check(settings, rawBody, headers);
        if (result != Result.VALID) {
            return new Verification(result, null);
        }
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        String secret = settings.signingSecret();
        WebhookChannelSettings.Scheme scheme = settings.scheme() == null ? WebhookChannelSettings.Scheme.EDDI_V1 : settings.scheme();
        String key = scheme == WebhookChannelSettings.Scheme.EDDI_V1
                ? "t:" + HmacSignatures.hmacSha256Hex(secret,
                        (timestampOf(headers.apply(EDDI_SIGNATURE_HEADER)) + ".").getBytes(StandardCharsets.UTF_8), body)
                : "b:" + HmacSignatures.hmacSha256Hex(secret, body);
        return new Verification(Result.VALID, key);
    }

    /**
     * The {@code t=} value of an eddi-v1 header already verified as well-formed.
     */
    private static String timestampOf(String header) {
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && "t".equals(part.substring(0, eq).trim())) {
                return part.substring(eq + 1).trim();
            }
        }
        throw new IllegalStateException("verified eddi-v1 header without t=");
    }

    private Result check(WebhookChannelSettings settings, byte[] rawBody, UnaryOperator<String> headers) {
        String secret = settings == null ? null : settings.signingSecret();
        if (secret == null || secret.isBlank() || secret.strip().startsWith("${vault:") || secret.contains("${vault:")) {
            // An unresolvable vault reference reads back as null — or, defensively,
            // as the literal reference. Neither may ever be used as a key.
            return Result.NO_SECRET;
        }
        byte[] body = rawBody == null ? new byte[0] : rawBody;
        return switch (settings.scheme() == null ? WebhookChannelSettings.Scheme.EDDI_V1 : settings.scheme()) {
            case EDDI_V1 -> verifyEddiV1(secret, body, headers.apply(EDDI_SIGNATURE_HEADER));
            case GITHUB -> verifyPrefixedHex(secret, body, headers.apply(GITHUB_SIGNATURE_HEADER), "sha256=");
            case GENERIC_HMAC -> verifyGeneric(settings, secret, body, headers.apply(settings.signatureHeader()));
        };
    }

    private Result verifyEddiV1(String secret, byte[] body, String header) {
        if (header == null || header.isBlank()) {
            return Result.MISSING_SIGNATURE;
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("t".equals(key)) {
                if (timestamp != null) {
                    // One timestamp, or none at all: with two, the MAC could be
                    // checked against one while something else read the other
                    // (the replay key did, so each junk t= minted a fresh key).
                    return Result.MALFORMED_SIGNATURE;
                }
                timestamp = value;
            } else if ("v1".equals(key) && !value.isEmpty()) {
                signatures.add(value.toLowerCase(Locale.ROOT));
            }
        }
        if (timestamp == null || signatures.isEmpty() || signatures.size() > 8) {
            return Result.MALFORMED_SIGNATURE;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Result.MALFORMED_SIGNATURE;
        }
        if (!HmacSignatures.isFresh(ts, clock.instant().getEpochSecond(), MAX_TIMESTAMP_AGE_SECONDS)) {
            return Result.STALE_TIMESTAMP;
        }
        String expected = HmacSignatures.hmacSha256Hex(secret, (timestamp + ".").getBytes(StandardCharsets.UTF_8), body);
        boolean match = false;
        for (String candidate : signatures) {
            // No early exit: every candidate is compared, so timing does not reveal
            // which one matched.
            match |= HmacSignatures.constantTimeEquals(expected, candidate);
        }
        return match ? Result.VALID : Result.INVALID_SIGNATURE;
    }

    private static Result verifyPrefixedHex(String secret, byte[] body, String header, String prefix) {
        if (header == null || header.isBlank()) {
            return Result.MISSING_SIGNATURE;
        }
        String value = header.trim();
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return Result.MALFORMED_SIGNATURE;
        }
        String expected = HmacSignatures.hmacSha256Hex(secret, body);
        return HmacSignatures.constantTimeEquals(expected, value.substring(prefix.length()).toLowerCase(Locale.ROOT))
                ? Result.VALID
                : Result.INVALID_SIGNATURE;
    }

    private static Result verifyGeneric(WebhookChannelSettings settings, String secret, byte[] body, String header) {
        if (header == null || header.isBlank()) {
            return Result.MISSING_SIGNATURE;
        }
        String value = header.trim();
        String prefix = settings.signaturePrefix() == null ? "" : settings.signaturePrefix();
        if (!prefix.isEmpty()) {
            if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return Result.MALFORMED_SIGNATURE;
            }
            value = value.substring(prefix.length());
        }
        byte[] expected = HmacSignatures.hmacSha256(secret, body);
        byte[] actual;
        try {
            actual = "base64".equals(settings.signatureEncoding())
                    ? Base64.getDecoder().decode(value)
                    : HexFormat.of().parseHex(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Result.MALFORMED_SIGNATURE;
        }
        return HmacSignatures.constantTimeEquals(expected, actual) ? Result.VALID : Result.INVALID_SIGNATURE;
    }
}
