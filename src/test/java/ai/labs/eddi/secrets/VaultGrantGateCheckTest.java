/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.secrets.VaultGrantGate.GrantCheck;
import ai.labs.eddi.secrets.VaultGrantGate.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The structured half of the gate: {@link VaultGrantGate#check} and the failure
 * it turns into, which is what the API now returns instead of a bare ERROR.
 */
@DisplayName("VaultGrantGate — structured check and failure")
class VaultGrantGateCheckTest {

    private static final String AGENT_ID = "0123456789abcdef01234567";

    private static VaultGrantGate gate(String mode, List<String> ungranted) {
        VaultGrantChecker checker = mock(VaultGrantChecker.class);
        when(checker.findUngrantedReferences(anyString(), any())).thenReturn(ungranted);
        return new VaultGrantGate(checker, mode);
    }

    @Test
    @DisplayName("enforce: the ungranted names are returned and the deploy is blocked")
    void enforceReturnsNamesAndBlocks() {
        GrantCheck check = gate("enforce", List.of("${vault:gemini-api-key}")).check(AGENT_ID, 1);

        assertEquals(Mode.ENFORCE, check.mode());
        assertTrue(check.checked());
        assertEquals(List.of("${vault:gemini-api-key}"), check.ungranted());
        assertTrue(check.blocked());
    }

    @Test
    @DisplayName("warn: the names are still returned, but nothing is blocked")
    void warnReturnsNamesWithoutBlocking() {
        GrantCheck check = gate("warn", List.of("${vault:gemini-api-key}")).check(AGENT_ID, 1);

        assertEquals(List.of("${vault:gemini-api-key}"), check.ungranted());
        assertTrue(check.checked());
        assertFalse(check.blocked());
    }

    @Test
    @DisplayName("off: the checker is never consulted and the check reports it did not run")
    void offDoesNotCheck() {
        VaultGrantChecker checker = mock(VaultGrantChecker.class);
        GrantCheck check = new VaultGrantGate(checker, "off").check(AGENT_ID, 1);

        assertFalse(check.checked());
        assertTrue(check.ungranted().isEmpty());
        assertFalse(check.blocked());
        verify(checker, never()).findUngrantedReferences(anyString(), any());
    }

    @Test
    @DisplayName("a check that throws is reported as not run, and never blocks")
    void failingCheckNeverBlocks() {
        VaultGrantChecker checker = mock(VaultGrantChecker.class);
        when(checker.findUngrantedReferences(anyString(), any())).thenThrow(new IllegalStateException("store down"));
        VaultGrantGate gate = new VaultGrantGate(checker, "enforce");

        GrantCheck check = gate.check(AGENT_ID, 1);
        assertFalse(check.checked());
        assertFalse(check.blocked());
        assertTrue(gate.mayDeploy(AGENT_ID, 1));
    }

    @Test
    @DisplayName("mayDeploy is unchanged: false only in enforce with a violation")
    void mayDeployUnchanged() {
        assertFalse(gate("enforce", List.of("${vault:k}")).mayDeploy(AGENT_ID, 1));
        assertTrue(gate("warn", List.of("${vault:k}")).mayDeploy(AGENT_ID, 1));
        assertTrue(gate("off", List.of("${vault:k}")).mayDeploy(AGENT_ID, 1));
        assertTrue(gate("enforce", List.of()).mayDeploy(AGENT_ID, 1));
    }

    @Test
    @DisplayName("the failure names each secret, its tenant, and the append call for THIS agent")
    void failureNamesSecretsAndFix() {
        DeploymentFailure failure = gate("enforce", List.of("${vault:gemini-api-key}", "${vault:team-a/search-key}")).check(AGENT_ID, 1)
                .toFailure(AGENT_ID, 1);

        assertEquals(DeploymentFailure.VAULT_GRANT_MISSING, failure.code());
        assertTrue(failure.isGrantMissing());
        assertEquals(List.of(new DeploymentFailure.SecretRef("default", "gemini-api-key", "${vault:gemini-api-key}"),
                new DeploymentFailure.SecretRef("team-a", "search-key", "${vault:team-a/search-key}")), failure.secrets());
        assertEquals(AGENT_ID, failure.fix().addAgentId());
        assertTrue(failure.fix().dryRunFirst());
        assertEquals(List.of("POST /secretstore/secrets/default/gemini-api-key/grant/agents/" + AGENT_ID,
                "POST /secretstore/secrets/team-a/search-key/grant/agents/" + AGENT_ID), failure.fix().endpoints());
        assertTrue(failure.message().contains("default/gemini-api-key"), failure.message());
        assertTrue(failure.message().contains("dryRun=true"), failure.message());
        // Says WHY a new agent hits this, not only "widen the grant".
        assertTrue(failure.message().contains("agent created after the grant was written"), failure.message());
    }

    @Test
    @DisplayName("a reference that is not a plain vault secret is listed without a fix endpoint")
    void unparseableReferenceHasNoFix() {
        String connection = "a connection that could not be read (${connection:crm})";
        DeploymentFailure failure = gate("enforce", List.of(connection)).check(AGENT_ID, 1).toFailure(AGENT_ID, 1);

        assertEquals(1, failure.secrets().size());
        assertNull(failure.secrets().get(0).keyName());
        assertEquals(connection, failure.secrets().get(0).reference());
        assertNull(failure.fix());
        assertTrue(failure.message().contains("not a plain vault secret"), failure.message());
    }

    @Test
    @DisplayName("parseVaultReference accepts only a whole vault reference")
    void parseVaultReferenceIsStrict() {
        assertEquals("k", VaultGrantGate.parseVaultReference("${vault:k}").keyName());
        assertEquals("t", VaultGrantGate.parseVaultReference("${eddivault:t/k}").tenantId());
        // Prose that merely CONTAINS a reference is not one.
        assertNull(VaultGrantGate.parseVaultReference("a reference assembled from global variables (${vault:k})"));
        assertNull(VaultGrantGate.parseVaultReference(null));
    }
}
