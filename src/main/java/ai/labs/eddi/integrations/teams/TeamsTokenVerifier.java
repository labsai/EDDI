/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Validates the JWT the Bot Connector service sends with every activity, as the
 * Bot Connector authentication documentation requires:
 * <ol>
 * <li>a {@code Bearer} token in the {@code Authorization} header;</li>
 * <li>{@code alg} exactly {@code RS256} (so {@code none} and the {@code HS256}
 * key-confusion trick are refused before any key is looked up) and a
 * {@code kid};</li>
 * <li>a valid signature by a key published in the Bot Connector's OpenID
 * metadata ({@link TeamsSigningKeys});</li>
 * <li>that key endorsed for the activity's {@code channelId}
 * ({@code msteams});</li>
 * <li>{@code iss} {@value #ISSUER};</li>
 * <li>{@code aud} the app id of a configured Teams integration;</li>
 * <li>{@code exp} (required) and {@code nbf} with five minutes of clock
 * skew;</li>
 * <li>a {@code serviceUrl} claim equal to the activity's
 * {@code serviceUrl}.</li>
 * </ol>
 * There is no setting that disables any of these checks; the documentation says
 * an implementation should not offer one, and this one does not.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamsTokenVerifier {

    public static final String ISSUER = "https://api.botframework.com";
    public static final String CHANNEL_ID = "msteams";
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    static final int MAX_TOKEN_LENGTH = 16 * 1024;

    /** Why a token was refused; {@link #VALID} when it was not. */
    public enum Outcome {
        VALID, MISSING, MALFORMED, ALGORITHM, UNKNOWN_KEY, SIGNATURE, ENDORSEMENT, ISSUER, AUDIENCE, EXPIRED, NOT_YET_VALID, SERVICE_URL
    }

    /**
     * The result of a verification. {@code appId} is the verified {@code aud},
     * lower-case, and set only when {@code outcome} is {@link Outcome#VALID}.
     */
    public record Verification(Outcome outcome, String appId) {
        public boolean valid() {
            return outcome == Outcome.VALID;
        }

        static Verification refused(Outcome outcome) {
            return new Verification(outcome, null);
        }
    }

    private final TeamsSigningKeys signingKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Inject
    public TeamsTokenVerifier(TeamsSigningKeys signingKeys, ObjectMapper objectMapper) {
        this(signingKeys, objectMapper, Clock.systemUTC());
    }

    TeamsTokenVerifier(TeamsSigningKeys signingKeys, ObjectMapper objectMapper, Clock clock) {
        this.signingKeys = signingKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param authorizationHeader
     *            the request's {@code Authorization} header
     * @param activityServiceUrl
     *            the {@code serviceUrl} of the activity in the request body
     * @param activityChannelId
     *            the {@code channelId} of that activity
     * @param knownAppId
     *            whether an app id (lower-case) belongs to a configured Teams
     *            integration
     */
    public Verification verify(String authorizationHeader, String activityServiceUrl, String activityChannelId,
                               Predicate<String> knownAppId) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Verification.refused(Outcome.MISSING);
        }
        String token = authorizationHeader.substring(7).trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return Verification.refused(Outcome.MALFORMED);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            return Verification.refused(Outcome.MALFORMED);
        }
        JsonNode header;
        JsonNode claims;
        byte[] signature;
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            header = objectMapper.readTree(decoder.decode(parts[0]));
            claims = objectMapper.readTree(decoder.decode(parts[1]));
            signature = decoder.decode(parts[2]);
        } catch (Exception e) {
            return Verification.refused(Outcome.MALFORMED);
        }
        if (header == null || !header.isObject() || claims == null || !claims.isObject()) {
            return Verification.refused(Outcome.MALFORMED);
        }

        // The algorithm is fixed by this verifier, not taken from the token: anything
        // but RS256 is refused before a key is looked up.
        if (!"RS256".equals(textOf(header, "alg"))) {
            return Verification.refused(Outcome.ALGORITHM);
        }
        String kid = textOf(header, "kid");
        if (kid == null || kid.isBlank()) {
            return Verification.refused(Outcome.MALFORMED);
        }
        TeamsSigningKeys.SigningKey key = signingKeys.find(kid);
        if (key == null) {
            return Verification.refused(Outcome.UNKNOWN_KEY);
        }
        if (!signatureValid(key, parts[0] + "." + parts[1], signature)) {
            return Verification.refused(Outcome.SIGNATURE);
        }
        if (!CHANNEL_ID.equals(activityChannelId) || !key.endorsements().contains(activityChannelId)) {
            return Verification.refused(Outcome.ENDORSEMENT);
        }
        if (!ISSUER.equals(textOf(claims, "iss"))) {
            return Verification.refused(Outcome.ISSUER);
        }
        String audience = textOf(claims, "aud");
        String appId = audience == null ? null : audience.toLowerCase(Locale.ROOT);
        if (appId == null || !TeamsChannelType.isGuid(appId) || !knownAppId.test(appId)) {
            return Verification.refused(Outcome.AUDIENCE);
        }
        Instant now = clock.instant();
        JsonNode exp = claims.get("exp");
        if (exp == null || !exp.isNumber() || Instant.ofEpochSecond(exp.asLong()).plus(CLOCK_SKEW).isBefore(now)) {
            return Verification.refused(Outcome.EXPIRED);
        }
        JsonNode nbf = claims.get("nbf");
        if (nbf != null && (!nbf.isNumber() || Instant.ofEpochSecond(nbf.asLong()).minus(CLOCK_SKEW).isAfter(now))) {
            return Verification.refused(Outcome.NOT_YET_VALID);
        }
        String serviceUrlClaim = textOf(claims, "serviceurl");
        if (serviceUrlClaim == null) {
            serviceUrlClaim = textOf(claims, "serviceUrl");
        }
        if (serviceUrlClaim == null || !serviceUrlClaim.equals(activityServiceUrl)) {
            return Verification.refused(Outcome.SERVICE_URL);
        }
        return new Verification(Outcome.VALID, appId);
    }

    private static boolean signatureValid(TeamsSigningKeys.SigningKey key, String signedPart, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key.publicKey());
            verifier.update(signedPart.getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
