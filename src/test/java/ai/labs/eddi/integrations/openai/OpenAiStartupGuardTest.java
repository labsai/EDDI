/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OpenAiStartupGuard}.
 * <p>
 * Each mitigating setting is asserted <em>individually</em>: a guard that only
 * happens to pass because two conditions are relaxed together would let a real
 * misconfiguration through.
 */
class OpenAiStartupGuardTest {

    private static void start(OpenAiCompatConfig config, boolean authorizationEnabled) {
        guard(config, authorizationEnabled, authorizationEnabled, mechanism(config, "permit", false))
                .onStart(new StartupEvent());
    }

    private static OpenAiStartupGuard guard(OpenAiCompatConfig config, boolean authorizationEnabled, boolean oidcEnabled,
                                            OpenAiPermitModeAuthMechanism mechanism) {
        return new OpenAiStartupGuard(config, authorizationEnabled, oidcEnabled, mechanism);
    }

    private static OpenAiPermitModeAuthMechanism mechanism(OpenAiCompatConfig config, String permissionPolicy,
                                                           boolean inclusive) {
        return new OpenAiPermitModeAuthMechanism(config, Optional.of(permissionPolicy),
                Optional.of(List.of("GET", "POST", "OPTIONS")), inclusive, 1001);
    }

    private static OpenAiCompatConfig sharedKeyConfig() {
        return OpenAiTestFixtures.config(b -> {
            b.enabled = true;
            b.apiKey = "sk-eddi-secret";
            b.httpPolicy = OpenAiCompatConfig.POLICY_PERMIT;
        });
    }

    @Test
    void sharedKeyUnderOidc_isNotFlagged_withTheShippedSetup() {
        var config = sharedKeyConfig();
        var guard = guard(config, true, true, mechanism(config, "permit", false));

        assertFalse(guard.isSharedKeyBlockedByOidc());
        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));
    }

    @Test
    void sharedKeyUnderOidc_isFlagged_withInclusiveAuthentication() {
        var config = sharedKeyConfig();
        var guard = guard(config, true, true, mechanism(config, "permit", true));

        assertTrue(guard.isSharedKeyBlockedByOidc());
        // A warning, not a failure: nothing is exposed, the key just cannot work.
        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));
    }

    @Test
    void sharedKeyUnderOidc_isFlagged_whenTheV1PermissionWasOverridden() {
        var config = sharedKeyConfig();

        assertTrue(guard(config, true, true, mechanism(config, "authenticated", false)).isSharedKeyBlockedByOidc());
    }

    @Test
    void sharedKey_isNotFlagged_withOidcOff() {
        var config = sharedKeyConfig();

        assertFalse(guard(config, false, false, mechanism(config, "permit", true)).isSharedKeyBlockedByOidc());
    }

    @Test
    void oidcMode_isNotFlagged() {
        var config = OpenAiTestFixtures.config(b -> b.httpPolicy = OpenAiCompatConfig.POLICY_AUTHENTICATED);

        assertFalse(guard(config, true, true, mechanism(config, "authenticated", false)).isSharedKeyBlockedByOidc());
    }

    @Test
    void disabledAdapter_isNotFlagged() {
        var config = OpenAiTestFixtures.config(b -> b.enabled = false);

        assertFalse(guard(config, true, true, mechanism(config, "permit", false)).isSharedKeyBlockedByOidc());
    }

    @Test
    void fails_whenEnabledAndUnauthenticatedAndAuthorizationOn() {
        var config = OpenAiTestFixtures.config(b -> {
            b.enabled = true;
            b.apiKey = null;
            b.httpPolicy = OpenAiCompatConfig.POLICY_PERMIT;
        });

        var exception = assertThrows(IllegalStateException.class, () -> start(config, true));

        assertTrue(exception.getMessage().contains("eddi.openai-compat.api-key"),
                "the failure must name the fix, not just the problem");
    }

    @Test
    void passes_whenApiKeyIsSet() {
        var config = OpenAiTestFixtures.config(b -> {
            b.enabled = true;
            b.apiKey = "sk-eddi-secret";
            b.httpPolicy = OpenAiCompatConfig.POLICY_PERMIT;
        });

        assertDoesNotThrow(() -> start(config, true));
    }

    @Test
    void passes_whenPolicyIsAuthenticated() {
        var config = OpenAiTestFixtures.config(b -> {
            b.enabled = true;
            b.apiKey = null;
            b.httpPolicy = OpenAiCompatConfig.POLICY_AUTHENTICATED;
        });

        assertDoesNotThrow(() -> start(config, true));
    }

    @Test
    void passes_whenAdapterDisabled() {
        var config = OpenAiTestFixtures.config(b -> {
            b.enabled = false;
            b.apiKey = null;
            b.httpPolicy = OpenAiCompatConfig.POLICY_PERMIT;
        });

        assertDoesNotThrow(() -> start(config, true));
    }

    @Test
    void passes_whenAuthorizationIsOff() {
        // Dev mode / network-isolated deployments legitimately run open.
        var config = OpenAiTestFixtures.config(b -> {
            b.enabled = true;
            b.apiKey = null;
            b.httpPolicy = OpenAiCompatConfig.POLICY_PERMIT;
        });

        assertDoesNotThrow(() -> start(config, false));
    }

    @Test
    void passes_whenAnonymityAllowed_butStillWarns() {
        var config = OpenAiTestFixtures.config(b -> {
            b.enabled = true;
            b.apiKey = "sk-eddi-secret";
            b.allowAnonymous = true;
        });

        assertDoesNotThrow(() -> start(config, true));
    }
}
