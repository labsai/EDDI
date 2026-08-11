/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.VaultGrantGate.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate decides deployment, so an unusable mode must fail loudly and a
 * violation must only block where the operator asked for blocking.
 */
@DisplayName("VaultGrantGate — enforcement modes")
class VaultGrantGateModeTest {

    private static VaultGrantGate gate(String mode, List<String> ungranted) {
        VaultGrantChecker checker = mock(VaultGrantChecker.class);
        when(checker.findUngrantedReferences(anyString(), any())).thenReturn(ungranted);
        return new VaultGrantGate(checker, mode);
    }

    @Test
    @DisplayName("the three supported modes parse, case-insensitively")
    void supportedModes() {
        assertEquals(Mode.OFF, Mode.parseStrict("off"));
        assertEquals(Mode.WARN, Mode.parseStrict("warn"));
        assertEquals(Mode.ENFORCE, Mode.parseStrict("  ENFORCE "));
        assertEquals(Mode.WARN, Mode.parseStrict(null));
        assertEquals(Mode.WARN, Mode.parseStrict("   "));
    }

    /**
     * {@code enforced} is a plausible typo, and silently falling back to warn turns
     * it into a control that is off while appearing on.
     */
    @Test
    @DisplayName("a near-miss like 'enforced' fails construction rather than defaulting")
    void nearMissIsRejected() {
        for (String invalid : new String[]{"enforced", "enforcing", "on", "true", "block"}) {
            var thrown = assertThrows(IllegalArgumentException.class, () -> Mode.parseStrict(invalid),
                    "'" + invalid + "' must be rejected rather than silently downgrading enforcement");
            assertTrue(thrown.getMessage().contains("off, warn, enforce"), thrown.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> gate("enforced", List.of()));
    }

    @Test
    @DisplayName("enforce blocks a provable violation")
    void enforceBlocks() {
        assertFalse(gate("enforce", List.of("${vault:someone-elses-key}")).mayDeploy("agent-1", 1));
    }

    @Test
    @DisplayName("warn allows the same violation, so an upgrade cannot take agents down")
    void warnAllows() {
        assertTrue(gate("warn", List.of("${vault:someone-elses-key}")).mayDeploy("agent-1", 1));
    }

    @Test
    @DisplayName("off does not even consult the checker")
    void offSkipsTheCheck() {
        VaultGrantChecker checker = mock(VaultGrantChecker.class);
        assertTrue(new VaultGrantGate(checker, "off").mayDeploy("agent-1", 1));
        verify(checker, never()).findUngrantedReferences(anyString(), any());
    }

    @Test
    @DisplayName("a granted agent is allowed in every mode")
    void grantedIsAllowed() {
        assertTrue(gate("enforce", List.of()).mayDeploy("agent-1", 1));
        assertTrue(gate("warn", List.of()).mayDeploy("agent-1", 1));
    }

    @Test
    @DisplayName("a checker failure never blocks a deployment")
    void checkerFailureAllows() {
        VaultGrantChecker checker = mock(VaultGrantChecker.class);
        when(checker.findUngrantedReferences(anyString(), any())).thenThrow(new RuntimeException("vault down"));
        assertTrue(new VaultGrantGate(checker, "enforce").mayDeploy("agent-1", 1));
    }
}
