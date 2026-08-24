/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HighValueSurfaceGuard}.
 * <p>
 * Each opt-out is asserted on its own. A guard that only passes because both
 * surfaces were opted out together would let the single-surface
 * misconfiguration — the realistic one, since an operator flips the flag they
 * read about — boot silently.
 */
class HighValueSurfaceGuardTest {

    private static void start(boolean authorizationEnabled, boolean mcpOptOut, boolean secretStoreOptOut, LaunchMode mode) {
        var guard = new HighValueSurfaceGuard(authorizationEnabled, mcpOptOut, secretStoreOptOut) {
            @Override
            LaunchMode getLaunchMode() {
                return mode;
            }
        };
        guard.onStart(new StartupEvent());
    }

    @Test
    void fails_inProduction_whenAuthorizationOffAndNeitherSurfaceOptedOut() {
        var exception = assertThrows(IllegalStateException.class, () -> start(false, false, false, LaunchMode.NORMAL));

        assertTrue(exception.getMessage().contains("/mcp"), "the failure must name /mcp");
        assertTrue(exception.getMessage().contains("/secretstore"), "the failure must name /secretstore");
        assertTrue(exception.getMessage().contains("EDDI_MCP_ALLOW_UNAUTHENTICATED"), "the failure must name the fix, not just the problem");
    }

    @Test
    void fails_whenOnlyMcpIsOptedOut() {
        var exception = assertThrows(IllegalStateException.class, () -> start(false, true, false, LaunchMode.NORMAL));

        assertTrue(exception.getMessage().contains("/secretstore"), "the still-open surface must be named");
        assertTrue(!exception.getMessage().contains("/mcp"), "an opted-out surface must not be reported as blocking");
    }

    @Test
    void fails_whenOnlySecretStoreIsOptedOut() {
        var exception = assertThrows(IllegalStateException.class, () -> start(false, false, true, LaunchMode.NORMAL));

        assertTrue(exception.getMessage().contains("/mcp"), "the still-open surface must be named");
        assertTrue(!exception.getMessage().contains("/secretstore"), "an opted-out surface must not be reported as blocking");
    }

    @Test
    void boots_whenBothSurfacesAreExplicitlyOptedOut() {
        assertDoesNotThrow(() -> start(false, true, true, LaunchMode.NORMAL));
    }

    @Test
    void boots_whenAuthorizationIsEnabled() {
        assertDoesNotThrow(() -> start(true, false, false, LaunchMode.NORMAL));
    }

    @Test
    void boots_inDevelopmentAndTest_withoutAnyOptOut() {
        assertDoesNotThrow(() -> start(false, false, false, LaunchMode.DEVELOPMENT));
        assertDoesNotThrow(() -> start(false, false, false, LaunchMode.TEST));
    }
}
