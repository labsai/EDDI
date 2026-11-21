/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Bot Connector's token-signing keys, read from its OpenID metadata
 * ({@value #OPENID_CONFIGURATION}) and the {@code jwks_uri} it names.
 * <p>
 * Refreshed at least every 24 hours, as the Bot Connector documentation asks,
 * and once more when a token names a key that is not known yet (keys may be
 * added at any time). That second refetch is reachable by anyone who can post
 * to the public endpoint, before any authentication, so it is single-flight and
 * limited globally to one per {@link #UNKNOWN_KID_REFETCH_INTERVAL}. A failed
 * refresh keeps the keys already held.
 * <p>
 * Only RSA keys are kept. The {@code jwks_uri} must be https on
 * {@value #KEYS_HOST}: the metadata document cannot redirect key retrieval
 * somewhere else.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamsSigningKeys {

    private static final Logger LOGGER = Logger.getLogger(TeamsSigningKeys.class);

    public static final String OPENID_CONFIGURATION = "https://login.botframework.com/v1/.well-known/openidconfiguration";
    static final String KEYS_HOST = "login.botframework.com";
    static final Duration REFRESH_INTERVAL = Duration.ofHours(24);
    static final Duration UNKNOWN_KID_REFETCH_INTERVAL = Duration.ofMinutes(1);
    static final long MAX_DOCUMENT_BYTES = 256L * 1024;

    /** A signing key and the channels it is endorsed for. */
    public record SigningKey(RSAPublicKey publicKey, Set<String> endorsements) {
    }

    /** Fetches a document; a seam for tests. */
    @FunctionalInterface
    interface Fetcher {
        String get(URI uri) throws IOException, InterruptedException;
    }

    private final Fetcher fetcher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final Object lock = new Object();
    private volatile Map<String, SigningKey> keys = Map.of();
    private volatile Instant fetchedAt = Instant.EPOCH;
    private Instant lastAttempt = Instant.EPOCH;

    @Inject
    public TeamsSigningKeys(OutboundHttp http, ObjectMapper objectMapper) {
        this(uri -> fetchWith(http, uri), objectMapper, Clock.systemUTC());
    }

    TeamsSigningKeys(Fetcher fetcher, ObjectMapper objectMapper, Clock clock) {
        this.fetcher = fetcher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * The key with id {@code kid}, or {@code null} when the Bot Connector does not
     * publish one (after at most one rate-limited refetch).
     */
    public SigningKey find(String kid) {
        if (kid == null || kid.isBlank()) {
            return null;
        }
        Instant now = clock.instant();
        if (fetchedAt.plus(REFRESH_INTERVAL).isBefore(now)) {
            refresh(REFRESH_INTERVAL);
        }
        SigningKey key = keys.get(kid);
        if (key == null) {
            refresh(UNKNOWN_KID_REFETCH_INTERVAL);
            key = keys.get(kid);
        }
        return key;
    }

    /**
     * Refetches unless another attempt happened less than {@code minInterval} ago.
     * Single-flight: concurrent callers wait for the one fetch and then see its
     * result.
     */
    private void refresh(Duration minInterval) {
        synchronized (lock) {
            Instant now = clock.instant();
            // A failed scheduled refresh is retried by the next unknown-kid lookup, at
            // most once a minute; the keys already held stay in use meanwhile.
            if (lastAttempt.plus(minInterval).isAfter(now)) {
                return;
            }
            lastAttempt = now;
            try {
                Map<String, SigningKey> loaded = load();
                if (loaded.isEmpty()) {
                    LOGGER.warn("The Bot Connector published no usable RSA signing keys; keeping the keys already held.");
                    return;
                }
                keys = Map.copyOf(loaded);
                fetchedAt = now;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Interrupted while fetching the Bot Connector signing keys; keeping the keys already held.");
            } catch (Exception e) {
                LOGGER.warnf("Could not fetch the Bot Connector signing keys (%s); keeping the keys already held.", e.getMessage());
            }
        }
    }

    private Map<String, SigningKey> load() throws IOException, InterruptedException {
        JsonNode metadata = objectMapper.readTree(fetcher.get(URI.create(OPENID_CONFIGURATION)));
        String jwksUri = metadata.path("jwks_uri").asText(null);
        URI keysUri = jwksUri == null ? null : URI.create(jwksUri);
        if (keysUri == null || !"https".equalsIgnoreCase(keysUri.getScheme()) || !KEYS_HOST.equalsIgnoreCase(keysUri.getHost())
                || keysUri.getPort() != -1 || keysUri.getUserInfo() != null) {
            throw new IOException("the OpenID metadata names a jwks_uri outside https://" + KEYS_HOST);
        }
        JsonNode jwks = objectMapper.readTree(fetcher.get(keysUri));
        Map<String, SigningKey> loaded = new HashMap<>();
        for (JsonNode jwk : jwks.path("keys")) {
            String kid = jwk.path("kid").asText(null);
            if (kid == null || kid.isBlank() || !"RSA".equals(jwk.path("kty").asText(null))) {
                continue;
            }
            String use = jwk.path("use").asText(null);
            if (use != null && !"sig".equals(use)) {
                continue;
            }
            try {
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText("")));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText("")));
                if (modulus.bitLength() < 2048) {
                    continue;
                }
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent));
                Set<String> endorsements = new HashSet<>();
                for (JsonNode endorsement : jwk.path("endorsements")) {
                    if (endorsement.isTextual()) {
                        endorsements.add(endorsement.asText());
                    }
                }
                loaded.put(kid, new SigningKey(publicKey, Set.copyOf(endorsements)));
            } catch (Exception e) {
                LOGGER.debugf("Skipping an unreadable Bot Connector key %s: %s", kid, e.getMessage());
            }
        }
        return loaded;
    }

    private static String fetchWith(OutboundHttp http, URI uri) throws IOException, InterruptedException {
        var request = OutboundRequest.get(Profile.DEFAULT, uri)
                .ssrf(SsrfPolicy.STRICT)
                .redirects(RedirectPolicy.NEVER)
                .header("Accept", "application/json")
                .build();
        OutboundResponse<String> response = http.send(request, BodyMode.string(MAX_DOCUMENT_BYTES, BodyMode.OnOverflow.FAIL));
        if (response.status() != 200) {
            throw new IOException("HTTP " + response.status() + " from " + OutboundHttp.withoutQuery(uri));
        }
        return response.body();
    }
}
