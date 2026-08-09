/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.internal.AgentDeploymentManagement.GrantEnforcement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code eddi.vault.grant-enforcement} gates a security control, so an
 * unrecognised value must be rejected rather than quietly meaning "warn".
 */
@DisplayName("eddi.vault.grant-enforcement — strict parsing")
class GrantEnforcementModeTest {

    @Test
    @DisplayName("the three supported modes parse, case-insensitively")
    void supportedModes() {
        assertEquals(GrantEnforcement.OFF, GrantEnforcement.parseStrict("off"));
        assertEquals(GrantEnforcement.WARN, GrantEnforcement.parseStrict("warn"));
        assertEquals(GrantEnforcement.ENFORCE, GrantEnforcement.parseStrict("enforce"));
        assertEquals(GrantEnforcement.ENFORCE, GrantEnforcement.parseStrict("  ENFORCE "));
    }

    @Test
    @DisplayName("absent means warn")
    void absentMeansWarn() {
        assertEquals(GrantEnforcement.WARN, GrantEnforcement.parseStrict(null));
        assertEquals(GrantEnforcement.WARN, GrantEnforcement.parseStrict("   "));
    }

    /**
     * The failure that motivated this: {@code enforced} is a plausible typo, and
     * silently falling back to warn turns it into a control that is off while
     * appearing to be on.
     */
    @Test
    @DisplayName("a near-miss like 'enforced' is rejected, never defaulted to warn")
    void nearMissIsRejected() {
        for (String invalid : new String[]{"enforced", "enforcing", "on", "true", "block"}) {
            var thrown = assertThrows(IllegalArgumentException.class, () -> GrantEnforcement.parseStrict(invalid),
                    "'" + invalid + "' must be rejected rather than silently downgrading enforcement");
            assertTrue(thrown.getMessage().contains("off, warn, enforce"), thrown.getMessage());
        }
    }
}
