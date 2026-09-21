/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.Principal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NamelessPrincipalAugmentor}.
 */
class NamelessPrincipalAugmentorTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final NamelessPrincipalAugmentor augmentor = new NamelessPrincipalAugmentor(Optional.empty(), clock::get);

    private static SecurityIdentity identityNamed(String name) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(name);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.isAnonymous()).thenReturn(false);
        return identity;
    }

    private SecurityIdentity augment(SecurityIdentity identity) {
        return augmentor.augment(identity, null).await().indefinitely();
    }

    @Test
    @DisplayName("a named identity passes through unchanged")
    void namedIdentity_passes() {
        var identity = identityNamed("user-123");
        assertSame(identity, augment(identity));
    }

    @Test
    @DisplayName("an anonymous identity passes through — it is nameless by design")
    void anonymousIdentity_passes() {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        assertSame(identity, augment(identity));
    }

    @ParameterizedTest(name = "name=[{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("an authenticated identity with a null or blank name fails authentication (401)")
    void namelessIdentity_rejected(String name) {
        var identity = identityNamed(name);
        var failure = assertThrows(AuthenticationFailedException.class, () -> augment(identity));
        assertEquals(NamelessPrincipalAugmentor.FAILURE_MESSAGE, failure.getMessage());
    }

    @Test
    @DisplayName("an authenticated identity with no principal at all fails authentication")
    void principalLessIdentity_rejected() {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        assertThrows(AuthenticationFailedException.class, () -> augment(identity));
    }

    @Test
    @DisplayName("the diagnostic names the default claims and the issuer and client of the token")
    void describe_defaultClaims_jwt() {
        var jwt = mock(JsonWebToken.class);
        when(jwt.getClaim("iss")).thenReturn("https://idp.example/realms/acme");
        when(jwt.getClaim("azp")).thenReturn("eddi-manager\nforged");
        var identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(jwt);

        String detail = augmentor.describe(identity);

        assertTrue(detail.contains("'upn', 'preferred_username' or 'sub'"), detail);
        assertTrue(detail.contains("quarkus.oidc.token.principal-claim"), detail);
        assertTrue(detail.contains("issuer='https://idp.example/realms/acme'"), detail);
        assertTrue(detail.contains("client='eddi-manager_forged'"), "claim values are log-sanitized: " + detail);
    }

    @Test
    @DisplayName("the diagnostic names the configured principal claim when one is set")
    void describe_configuredClaim() {
        var configured = new NamelessPrincipalAugmentor(Optional.of("email"), clock::get);
        String detail = configured.describe(identityNamed(null));

        assertTrue(detail.contains("configured principal claim 'email'"), detail);
        assertFalse(detail.contains("preferred_username"), detail);
        assertFalse(detail.contains("issuer="), "a non-JWT principal has no token to describe: " + detail);
    }

    @Test
    @DisplayName("the WARN fires on the first rejection and then at most once per interval")
    void shouldWarn_isThrottled() {
        assertTrue(augmentor.shouldWarn(), "first rejection warns");
        assertFalse(augmentor.shouldWarn(), "an immediate repeat does not");

        clock.addAndGet(NamelessPrincipalAugmentor.WARN_INTERVAL_NANOS - 1);
        assertFalse(augmentor.shouldWarn(), "still inside the interval");

        clock.addAndGet(1);
        assertTrue(augmentor.shouldWarn(), "the interval has elapsed");
        assertFalse(augmentor.shouldWarn());
    }
}
