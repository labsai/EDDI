/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The record's accessors are generated and not worth asserting. Two things here
 * are not generated and both bite in production if they change.
 * <p>
 * The first is the hand-written {@code toString}: a record's default one prints
 * every component, so a {@code TokenResponse} that reaches a log line at DEBUG,
 * an exception message or a debugger's variable view would print a live access
 * token in the clear. The second is {@link TokenResponse#DEFAULT_LIFETIME},
 * whose value has to sit between two other constants to be useful at all.
 */
class TokenResponseTest {

    private static final String ACCESS_TOKEN = "ya29.LIVE-ACCESS-MATERIAL";
    private static final String REFRESH_TOKEN = "1//0g-LIVE-REFRESH-MATERIAL";

    @Test
    @DisplayName("printing a token response never prints the access token")
    void neverPrintsTheAccessToken() {
        var printed = new TokenResponse(ACCESS_TOKEN, REFRESH_TOKEN, Duration.ofHours(1), List.of("drive.readonly")).toString();

        assertFalse(printed.contains(ACCESS_TOKEN), printed);
        assertFalse(printed.contains(REFRESH_TOKEN), printed);
        assertTrue(printed.contains("<REDACTED>"), printed);
    }

    @Test
    @DisplayName("the non-secret fields stay visible, because they are what a stuck refresh is diagnosed from")
    void keepsTheDiagnosticFields() {
        var printed = new TokenResponse(ACCESS_TOKEN, null, Duration.ofSeconds(20), List.of("drive.readonly", "drive.file")).toString();

        assertTrue(printed.contains("PT20S"), printed);
        assertTrue(printed.contains("drive.readonly"), printed);
        assertTrue(printed.contains("drive.file"), printed);
    }

    @Test
    @DisplayName("a missing refresh token is distinguishable from a present one without printing either")
    void distinguishesAMissingRefreshTokenFromAPresentOne() {
        // Whether the provider rotated is the first question asked when a connection
        // starts demanding reconnects, and the answer must not cost a token in a log.
        var rotated = new TokenResponse(ACCESS_TOKEN, REFRESH_TOKEN, Duration.ofHours(1), List.of()).toString();
        var notRotated = new TokenResponse(ACCESS_TOKEN, null, Duration.ofHours(1), List.of()).toString();

        assertTrue(rotated.contains("refreshToken=<REDACTED>"), rotated);
        assertTrue(notRotated.contains("refreshToken=none"), notRotated);
        assertFalse(notRotated.contains("refreshToken=<REDACTED>"), notRotated);
    }

    @Test
    @DisplayName("the assumed lifetime outlives the slowest exchange that could have produced it")
    void assumedLifetimeOutlivesTheSlowestPossibleExchange() {
        // A provider that omits expires_in gets this lifetime counted from the moment
        // the response is parsed. Shorter than the exchange's own ceiling and a token
        // minted after a slow provider would be born expired, so every call refreshes.
        assertTrue(TokenResponse.DEFAULT_LIFETIME.compareTo(OAuthTokenClient.MAX_TIMEOUT) > 0,
                "a token cannot be born already expired");
        assertTrue(TokenResponse.DEFAULT_LIFETIME.compareTo(Duration.ofHours(1)) < 0,
                "a long assumed lifetime is 'never expires' by another name — the failure it hides is an opaque 401 mid-turn");
    }
}
