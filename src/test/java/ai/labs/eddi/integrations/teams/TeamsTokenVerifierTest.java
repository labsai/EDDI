/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import ai.labs.eddi.integrations.teams.TeamsTokenVerifier.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One refused token per check of {@link TeamsTokenVerifier}, against a valid
 * baseline that passes. Each test changes exactly one thing in that baseline.
 */
class TeamsTokenVerifierTest {

    static final String APP_ID = "11111111-2222-3333-4444-555555555555";
    static final String SERVICE_URL = "https://smba.trafficmanager.net/emea/";
    static final String KID = "key-1";
    static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static KeyPair signingKey;
    private static KeyPair otherKey;

    private final AtomicInteger fetches = new AtomicInteger();
    private Clock clock;
    private TeamsTokenVerifier verifier;
    private List<String> endorsements;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        endorsements = List.of("msteams", "webchat");
        TeamsSigningKeys keys = new TeamsSigningKeys(this::fetch, MAPPER, clock);
        verifier = new TeamsTokenVerifier(keys, MAPPER, clock);
    }

    private String fetch(URI uri) throws Exception {
        if (uri.toString().equals(TeamsSigningKeys.OPENID_CONFIGURATION)) {
            return "{\"jwks_uri\":\"https://login.botframework.com/v1/.well-known/keys\"}";
        }
        fetches.incrementAndGet();
        RSAPublicKey key = (RSAPublicKey) signingKey.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("kid", KID);
        jwk.put("n", b64(unsigned(key.getModulus())));
        jwk.put("e", b64(unsigned(key.getPublicExponent())));
        jwk.put("endorsements", endorsements);
        return MAPPER.writeValueAsString(Map.of("keys", List.of(jwk)));
    }

    // --- token building ---

    private Map<String, Object> header() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", KID);
        return header;
    }

    private Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", TeamsTokenVerifier.ISSUER);
        claims.put("aud", APP_ID);
        claims.put("serviceurl", SERVICE_URL);
        claims.put("nbf", NOW.minusSeconds(60).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(3600).getEpochSecond());
        return claims;
    }

    private static String rs256(Map<String, Object> header, Map<String, Object> claims, KeyPair key) throws Exception {
        String signed = b64(MAPPER.writeValueAsBytes(header)) + "." + b64(MAPPER.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(signed.getBytes(StandardCharsets.US_ASCII));
        return signed + "." + b64(signature.sign());
    }

    private TeamsTokenVerifier.Verification verify(String token) {
        return verifier.verify("Bearer " + token, SERVICE_URL, "msteams", APP_ID::equals);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    // --- baseline ---

    @Test
    void validTokenPasses() throws Exception {
        var result = verify(rs256(header(), claims(), signingKey));
        assertEquals(Outcome.VALID, result.outcome());
        assertEquals(APP_ID, result.appId());
    }

    @Test
    void upperCaseAudienceIsNormalised() throws Exception {
        var claims = claims();
        claims.put("aud", APP_ID.toUpperCase());
        assertEquals(APP_ID, verify(rs256(header(), claims, signingKey)).appId());
    }

    // --- one failure per check ---

    @Test
    void missingBearerIsRefused() {
        assertEquals(Outcome.MISSING, verifier.verify(null, SERVICE_URL, "msteams", APP_ID::equals).outcome());
        assertEquals(Outcome.MISSING, verifier.verify("Basic abc", SERVICE_URL, "msteams", APP_ID::equals).outcome());
    }

    @Test
    void wrongIssuerIsRefused() throws Exception {
        var claims = claims();
        claims.put("iss", "https://sts.windows.net/" + APP_ID + "/");
        var result = verify(rs256(header(), claims, signingKey));
        assertEquals(Outcome.ISSUER, result.outcome());
        assertNull(result.appId());
    }

    @Test
    void wrongAudienceIsRefused() throws Exception {
        var claims = claims();
        claims.put("aud", "99999999-2222-3333-4444-555555555555");
        assertEquals(Outcome.AUDIENCE, verify(rs256(header(), claims, signingKey)).outcome());
    }

    @Test
    void audienceArrayIsRefused() throws Exception {
        var claims = claims();
        claims.put("aud", List.of(APP_ID));
        assertEquals(Outcome.AUDIENCE, verify(rs256(header(), claims, signingKey)).outcome());
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        var claims = claims();
        claims.put("exp", NOW.minusSeconds(301).getEpochSecond());
        assertEquals(Outcome.EXPIRED, verify(rs256(header(), claims, signingKey)).outcome());
    }

    @Test
    void expiredWithinSkewIsAccepted() throws Exception {
        var claims = claims();
        claims.put("exp", NOW.minusSeconds(299).getEpochSecond());
        assertEquals(Outcome.VALID, verify(rs256(header(), claims, signingKey)).outcome());
    }

    @Test
    void tokenWithoutExpiryIsRefused() throws Exception {
        var claims = claims();
        claims.remove("exp");
        assertEquals(Outcome.EXPIRED, verify(rs256(header(), claims, signingKey)).outcome());
    }

    @Test
    void notYetValidTokenIsRefused() throws Exception {
        var claims = claims();
        claims.put("nbf", NOW.plusSeconds(301).getEpochSecond());
        assertEquals(Outcome.NOT_YET_VALID, verify(rs256(header(), claims, signingKey)).outcome());
    }

    @Test
    void serviceUrlMismatchIsRefused() throws Exception {
        var claims = claims();
        claims.put("serviceurl", "https://attacker.example/");
        assertEquals(Outcome.SERVICE_URL, verify(rs256(header(), claims, signingKey)).outcome());
    }

    @Test
    void missingServiceUrlClaimIsRefused() throws Exception {
        var claims = claims();
        claims.remove("serviceurl");
        assertEquals(Outcome.SERVICE_URL, verify(rs256(header(), claims, signingKey)).outcome());
    }

    @Test
    void keyWithoutTeamsEndorsementIsRefused() throws Exception {
        endorsements = List.of("webchat");
        assertEquals(Outcome.ENDORSEMENT, verify(rs256(header(), claims(), signingKey)).outcome());
    }

    @Test
    void activityFromAnotherChannelIsRefused() throws Exception {
        var result = verifier.verify("Bearer " + rs256(header(), claims(), signingKey), SERVICE_URL, "webchat", APP_ID::equals);
        assertEquals(Outcome.ENDORSEMENT, result.outcome());
    }

    @Test
    void unknownKidIsRefused() throws Exception {
        var header = header();
        header.put("kid", "not-published");
        assertEquals(Outcome.UNKNOWN_KEY, verify(rs256(header, claims(), signingKey)).outcome());
    }

    @Test
    void signatureByAnotherKeyIsRefused() throws Exception {
        assertEquals(Outcome.SIGNATURE, verify(rs256(header(), claims(), otherKey)).outcome());
    }

    @Test
    void tamperedClaimsAreRefused() throws Exception {
        String[] parts = rs256(header(), claims(), signingKey).split("\\.");
        var claims = claims();
        claims.put("aud", APP_ID);
        claims.put("serviceurl", "https://attacker.example/");
        String forged = parts[0] + "." + b64(MAPPER.writeValueAsBytes(claims)) + "." + parts[2];
        assertEquals(Outcome.SIGNATURE, verify(forged).outcome());
    }

    @Test
    void algNoneIsRefused() throws Exception {
        var header = header();
        header.put("alg", "none");
        String token = b64(MAPPER.writeValueAsBytes(header)) + "." + b64(MAPPER.writeValueAsBytes(claims())) + ".";
        assertEquals(Outcome.MALFORMED, verify(token).outcome());
        String withSignature = b64(MAPPER.writeValueAsBytes(header)) + "." + b64(MAPPER.writeValueAsBytes(claims())) + ".AAAA";
        assertEquals(Outcome.ALGORITHM, verify(withSignature).outcome());
    }

    @Test
    void hs256KeyConfusionIsRefused() throws Exception {
        // The classic confusion: an HMAC "signed" with the RSA public key's bytes.
        var header = header();
        header.put("alg", "HS256");
        String signed = b64(MAPPER.writeValueAsBytes(header)) + "." + b64(MAPPER.writeValueAsBytes(claims()));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey.getPublic().getEncoded(), "HmacSHA256"));
        String token = signed + "." + b64(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(Outcome.ALGORITHM, verify(token).outcome());
    }

    @Test
    void rs512IsRefused() throws Exception {
        var header = header();
        header.put("alg", "RS512");
        assertEquals(Outcome.ALGORITHM, verify(rs256(header, claims(), signingKey)).outcome());
    }

    @Test
    void missingKidIsRefused() throws Exception {
        var header = header();
        header.remove("kid");
        assertEquals(Outcome.MALFORMED, verify(rs256(header, claims(), signingKey)).outcome());
    }

    @Test
    void garbageIsRefused() {
        assertEquals(Outcome.MALFORMED, verify("a.b").outcome());
        assertEquals(Outcome.MALFORMED, verify("!!!.@@@.###").outcome());
        assertEquals(Outcome.MALFORMED, verify("x".repeat(TeamsTokenVerifier.MAX_TOKEN_LENGTH + 1)).outcome());
    }

    // --- key retrieval ---

    @Test
    void unknownKidRefetchIsRateLimited() throws Exception {
        var header = header();
        header.put("kid", "not-published");
        String token = rs256(header, claims(), signingKey);
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            outcomes.add(verify(token).outcome());
        }
        assertTrue(outcomes.stream().allMatch(Outcome.UNKNOWN_KEY::equals));
        // One initial fetch; the unknown-kid refetches inside the same minute are
        // refused.
        assertEquals(1, fetches.get());
    }

    @Test
    void jwksUriOffBotFrameworkIsNotFollowed() throws Exception {
        TeamsSigningKeys keys = new TeamsSigningKeys(uri -> {
            if (uri.toString().equals(TeamsSigningKeys.OPENID_CONFIGURATION)) {
                return "{\"jwks_uri\":\"https://attacker.example/keys\"}";
            }
            throw new AssertionError("followed a jwks_uri off login.botframework.com: " + uri);
        }, MAPPER, clock);
        var result = new TeamsTokenVerifier(keys, MAPPER, clock).verify("Bearer " + rs256(header(), claims(), signingKey), SERVICE_URL,
                "msteams", APP_ID::equals);
        assertEquals(Outcome.UNKNOWN_KEY, result.outcome());
    }

    @Test
    void failedRefreshKeepsKnownKeys() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MutableClock mutable = new MutableClock(NOW);
        TeamsSigningKeys keys = new TeamsSigningKeys(uri -> {
            if (calls.incrementAndGet() > 2) {
                throw new IOException("down");
            }
            return fetch(uri);
        }, MAPPER, mutable);
        assertNotNull(keys.find(KID));
        assertEquals(2, calls.get());
        // A day later the scheduled refresh fails: the known key is still used.
        mutable.now = NOW.plus(Duration.ofHours(25));
        assertNotNull(keys.find(KID));
        assertEquals(3, calls.get());
    }

    @Test
    void scheduledRefreshPicksUpRotatedKeys() throws Exception {
        MutableClock mutable = new MutableClock(NOW);
        TeamsSigningKeys keys = new TeamsSigningKeys(this::fetch, MAPPER, mutable);
        assertNotNull(keys.find(KID));
        assertEquals(1, fetches.get());
        mutable.now = NOW.plus(Duration.ofHours(23));
        keys.find(KID);
        assertEquals(1, fetches.get());
        mutable.now = NOW.plus(Duration.ofHours(25));
        keys.find(KID);
        assertEquals(2, fetches.get());
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
