/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grant's accessors are plumbing; its one decision is
 * {@link ConnectionGrant#isAccessTokenUsable(Instant, Duration)}, and it is the
 * decision every outbound call to a connected SaaS depends on.
 * <p>
 * Both of its failure modes are expensive and neither is reproducible from a
 * bug report. Too permissive, and a token expiring inside the margin is spent
 * against a provider whose clock differs by a second — a 401 that lands
 * mid-turn and never reproduces. Too strict, and a perfectly live token is
 * refreshed on every single call, which hammers the token endpoint and, with a
 * rotating refresh token, races itself.
 */
class ConnectionGrantTest {

    private static final Duration MARGIN = Duration.ofSeconds(30);

    private static ConnectionGrant grant(ConnectionGrant.Status status, Instant expiresAt) {
        var grant = new ConnectionGrant();
        grant.setTenantId("acme");
        grant.setConnectionName("drive");
        grant.setPrincipal("alice");
        grant.setEncryptedAccessToken("sealed:at");
        grant.setAccessTokenIv("iv-access");
        grant.setStatus(status);
        grant.setExpiresAt(expiresAt);
        return grant;
    }

    @Test
    @DisplayName("a token with an hour left is usable")
    void usableWhenComfortablyValid() {
        Instant now = Instant.now();

        assertTrue(grant(ConnectionGrant.Status.ACTIVE, now.plus(Duration.ofHours(1))).isAccessTokenUsable(now, MARGIN));
    }

    @Test
    @DisplayName("a token expiring inside the margin is not usable, however alive it technically still is")
    void refusesATokenExpiringInsideTheMargin() {
        Instant now = Instant.now();

        assertFalse(grant(ConnectionGrant.Status.ACTIVE, now.plusMillis(200)).isAccessTokenUsable(now, MARGIN),
                "200ms of remaining life passes a naive check and is then rejected by a provider whose clock differs by a second");
        assertFalse(grant(ConnectionGrant.Status.ACTIVE, now.plusSeconds(29)).isAccessTokenUsable(now, MARGIN));
    }

    @Test
    @DisplayName("expiry exactly at the margin boundary is not usable")
    void refusesExactlyAtTheMarginBoundary() {
        Instant now = Instant.now();

        assertFalse(grant(ConnectionGrant.Status.ACTIVE, now.plus(MARGIN)).isAccessTokenUsable(now, MARGIN),
                "the margin is the point at which a refresh is due, so reaching it is already too late");
        assertTrue(grant(ConnectionGrant.Status.ACTIVE, now.plus(MARGIN).plusNanos(1)).isAccessTokenUsable(now, MARGIN),
                "one instant past the margin must still be usable, or the margin is silently larger than configured");
    }

    @Test
    @DisplayName("an already expired token is not usable even with no margin at all")
    void refusesAnExpiredToken() {
        Instant now = Instant.now();

        assertFalse(grant(ConnectionGrant.Status.ACTIVE, now.minusSeconds(1)).isAccessTokenUsable(now, Duration.ZERO));
        assertFalse(grant(ConnectionGrant.Status.ACTIVE, now).isAccessTokenUsable(now, Duration.ZERO),
                "expiring this very instant is expired");
    }

    @Test
    @DisplayName("only an ACTIVE grant is usable, however fresh its token looks")
    void refusesEveryNonActiveStatus() {
        Instant now = Instant.now();
        Instant farFuture = now.plus(Duration.ofDays(1));

        for (var status : List.of(ConnectionGrant.Status.EXPIRED, ConnectionGrant.Status.REVOKED, ConnectionGrant.Status.REFRESH_FAILED)) {
            assertFalse(grant(status, farFuture).isAccessTokenUsable(now, MARGIN), status.name());
        }
    }

    @Test
    @DisplayName("a grant holding no ciphertext is not usable, whatever its expiry says")
    void refusesAGrantWithNoCiphertext() {
        Instant now = Instant.now();
        var grant = grant(ConnectionGrant.Status.ACTIVE, now.plus(Duration.ofHours(1)));
        grant.setEncryptedAccessToken(null);

        assertFalse(grant.isAccessTokenUsable(now, MARGIN),
                "a row written by the claim protocol before the token endpoint answered has an expiry and no token yet");
    }

    @Test
    @DisplayName("a grant with no expiry is refused rather than dereferenced")
    void refusesAGrantWithNoExpiry() {
        var grant = grant(ConnectionGrant.Status.ACTIVE, null);

        assertFalse(grant.isAccessTokenUsable(Instant.now(), MARGIN));
    }

    @Test
    @DisplayName("a freshly constructed grant is ACTIVE, so a newly minted token is usable the moment it is stored")
    void isBornActive() {
        Instant now = Instant.now();
        var minted = new ConnectionGrant();
        minted.setEncryptedAccessToken("sealed:at");
        minted.setExpiresAt(now.plus(Duration.ofHours(1)));

        assertTrue(minted.isAccessTokenUsable(now, MARGIN), "a default status of anything else would make every first call refresh");
    }

    @Test
    @DisplayName("printing a grant never prints token material, not even its length")
    void neverPrintsTokenMaterial() {
        var grant = grant(ConnectionGrant.Status.ACTIVE, Instant.parse("2026-08-22T10:15:30Z"));
        grant.setEncryptedRefreshToken("sealed:rt");
        grant.setRefreshTokenIv("iv-refresh");
        grant.setDekId("acme:2");
        grant.setVersion(7);

        String printed = grant.toString();

        assertFalse(printed.contains("sealed:at"), printed);
        assertFalse(printed.contains("sealed:rt"), printed);
        assertFalse(printed.contains("iv-access"), printed);
        assertFalse(printed.contains("iv-refresh"), printed);
        assertTrue(printed.contains("acme"), printed);
        assertTrue(printed.contains("drive"), printed);
        assertTrue(printed.contains("alice"), printed);
        assertTrue(printed.contains("ACTIVE"), printed);
        assertTrue(printed.contains("2026-08-22T10:15:30Z"), printed);
        assertTrue(printed.contains("version=7"), printed);
    }
}
