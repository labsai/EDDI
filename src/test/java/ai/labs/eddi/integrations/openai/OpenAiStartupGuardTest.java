/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
        new OpenAiStartupGuard(config, authorizationEnabled).onStart(new StartupEvent());
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
