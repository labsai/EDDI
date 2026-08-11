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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        // null/blank are covered by absentMeansShippedDefault — they resolve to the
        // shipped default, not to a fixed mode named here.
    }

    /**
     * Absent or blank must resolve to the SHIPPED default, never to something
     * weaker. A bundled {@code application.properties} saying {@code enforce}
     * beside a code fallback saying {@code warn} means an external configuration
     * that omits or blanks the key runs with the control off while every visible
     * sign still says it is on.
     */
    @Test
    @DisplayName("absent or blank resolves to the shipped default, never to something weaker")
    void absentMeansShippedDefault() {
        Mode shipped = Mode.valueOf(VaultGrantGate.DEFAULT_MODE_NAME.toUpperCase());

        assertEquals(shipped, Mode.parseStrict(null));
        assertEquals(shipped, Mode.parseStrict(""));
        assertEquals(shipped, Mode.parseStrict("   "));
        // Pinned concretely as well as relatively: asserting only "equals the
        // constant" would still pass if the constant itself were weakened to warn.
        assertEquals(Mode.ENFORCE, shipped, "the shipped default must be enforce");
    }

    /**
     * The bundled property and the code fallback are two definitions of one fact,
     * and the whole finding here was that they had drifted apart.
     * <p>
     * Read from {@code src/main/resources} on disk rather than the classpath:
     * {@code src/test/resources/application.properties} shadows the shipped file
     * during tests and does NOT set this key, so a classpath read would assert
     * against the wrong file — and, being absent there, would assert nothing at
     * all. (That shadowing is also why the drift this test guards was invisible in
     * the test environment: tests take the code fallback, which is exactly the
     * value that had diverged.)
     */
    @Test
    @DisplayName("the bundled application.properties matches the code fallback")
    void bundledPropertyMatchesTheCodeFallback() throws Exception {
        var shippedProperties = java.nio.file.Path.of("src", "main", "resources", "application.properties");
        assertTrue(java.nio.file.Files.exists(shippedProperties), "expected the shipped application.properties at " + shippedProperties);

        var configured = java.nio.file.Files.readAllLines(shippedProperties).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("eddi.vault.grant-enforcement="))
                .map(line -> line.substring(line.indexOf('=') + 1).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("eddi.vault.grant-enforcement is not set in application.properties"));

        assertEquals(VaultGrantGate.DEFAULT_MODE_NAME, configured,
                "the bundled property and DEFAULT_MODE_NAME must not drift — that drift IS the vulnerability");
    }

    @Test
    @DisplayName("turning enforcement down is explicit, never implicit")
    void weakeningIsExplicit() {
        assertEquals(Mode.WARN, Mode.parseStrict("warn"));
        assertEquals(Mode.OFF, Mode.parseStrict("off"));
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
