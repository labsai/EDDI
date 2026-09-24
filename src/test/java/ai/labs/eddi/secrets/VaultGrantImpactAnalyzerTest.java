/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.secrets.VaultGrantImpactAnalyzer.AffectedAgent;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link VaultGrantImpactAnalyzer} — the warning an operator
 * sees before narrowing a grant.
 */
class VaultGrantImpactAnalyzerTest {

    private static final SecretReference SECRET = new SecretReference("default", "llm-api-key");

    private VaultGrantChecker checker;
    private IAgentFactory agentFactory;
    private VaultGrantImpactAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        checker = mock(VaultGrantChecker.class);
        agentFactory = mock(IAgentFactory.class);
        when(agentFactory.getAllDeployedAgents(any())).thenReturn(List.of());
        analyzer = new VaultGrantImpactAnalyzer(checker, agentFactory);
    }

    /** A deployed agent in the runtime registry. */
    private static IAgent agent(String id, Integer version, Deployment.Status status) {
        IAgent agent = mock(IAgent.class);
        when(agent.getAgentId()).thenReturn(id);
        when(agent.getAgentVersion()).thenReturn(version);
        when(agent.getDeploymentStatus()).thenReturn(status);
        return agent;
    }

    /**
     * Puts agents in production only, so the environment in the result is pinned.
     */
    private void deployedInProduction(IAgent... agents) throws Exception {
        when(agentFactory.getAllDeployedAgents(Deployment.Environment.production)).thenReturn(List.of(agents));
        when(agentFactory.getAllDeployedAgents(Deployment.Environment.test)).thenReturn(List.of());
    }

    @Test
    @DisplayName("an agent that uses the secret and is not on the new list is reported")
    void reportsAnAgentLosingAccess() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));
        when(checker.references(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(true);

        List<AffectedAgent> affected = analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess();

        assertEquals(List.of(new AffectedAgent("agentTwo", 3, "production")), affected);
    }

    @Test
    @DisplayName("an agent still on the new list is not reported, and is not even scanned")
    void skipsAgentsStillGranted() throws Exception {
        deployedInProduction(agent("agentOne", 1, Deployment.Status.READY));

        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess().isEmpty());

        // Reading an agent's whole workflow tree to answer a question the grant
        // list already answers is wasted work on a page-load-blocking call.
        verify(checker, never()).references(any(), any(), any());
    }

    @Test
    @DisplayName("an agent that does not reference the secret is not reported")
    void skipsAgentsThatDoNotUseTheSecret() throws Exception {
        deployedInProduction(agent("agentThree", 2, Deployment.Status.READY));
        when(checker.references(eq("agentThree"), anyInt(), eq(SECRET))).thenReturn(false);

        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess().isEmpty());
    }

    @Test
    @DisplayName("the wildcard short-circuits: nothing can lose access to a secret everyone may use")
    void wildcardMeansNoImpact() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));

        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of("*")).agentsLosingAccess().isEmpty());

        // And it does so without touching either collaborator, so widening a grant
        // costs no agent scan at all.
        verifyNoInteractions(agentFactory);
        verifyNoInteractions(checker);
    }

    @Test
    @DisplayName("null and empty grant lists short-circuit too — both already mean 'everyone'")
    void absentGrantMeansNoImpact() {
        assertTrue(analyzer.agentsLosingAccess(SECRET, null).agentsLosingAccess().isEmpty());
        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of()).agentsLosingAccess().isEmpty());
        verifyNoInteractions(agentFactory);
    }

    @Test
    @DisplayName("an agent that is registered but not READY is not reported")
    void skipsAgentsThatAreNotReady() throws Exception {
        // A deployment that never came up is not a deployment this change breaks.
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.ERROR));
        when(checker.references(any(), any(), any())).thenReturn(true);

        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess().isEmpty());
    }

    @Test
    @DisplayName("both environments are scanned")
    void scansEveryEnvironment() throws Exception {
        // Built before the stubbing, not inside thenReturn: creating a mock while an
        // outer when() is still open is an UnfinishedStubbingException.
        IAgent inProduction = agent("agentTwo", 3, Deployment.Status.READY);
        IAgent inTest = agent("agentFour", 1, Deployment.Status.READY);
        when(agentFactory.getAllDeployedAgents(Deployment.Environment.production)).thenReturn(List.of(inProduction));
        when(agentFactory.getAllDeployedAgents(Deployment.Environment.test)).thenReturn(List.of(inTest));
        when(checker.references(any(), any(), eq(SECRET))).thenReturn(true);

        List<AffectedAgent> affected = analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess();

        assertEquals(2, affected.size());
        assertEquals(List.of("production", "test"), affected.stream().map(AffectedAgent::environment).sorted().toList());
    }

    @Test
    @DisplayName("an older READY version is scanned even when a newer version is registered but not READY")
    void findsTheVersionActuallyServing() throws Exception {
        // v3 failed to come up; v2 is what users are talking to. Looking only at the
        // latest version per id would skip v3 as not READY and never see v2.
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.ERROR), agent("agentTwo", 2, Deployment.Status.READY));
        when(checker.references(eq("agentTwo"), eq(2), eq(SECRET))).thenReturn(true);

        assertEquals(List.of(new AffectedAgent("agentTwo", 2, "production")),
                analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess());
    }

    @Test
    @DisplayName("every READY version is reported, and each only once")
    void reportsEachReadyVersion() throws Exception {
        IAgent v1 = agent("agentTwo", 1, Deployment.Status.READY);
        IAgent v2 = agent("agentTwo", 2, Deployment.Status.READY);
        deployedInProduction(v1, v2, v2);
        when(checker.references(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(true);

        assertEquals(List.of(new AffectedAgent("agentTwo", 1, "production"), new AffectedAgent("agentTwo", 2, "production")),
                analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess());
    }

    @Test
    @DisplayName("a registry that cannot be listed yields no warning rather than an exception, and is reported as incomplete")
    void aFailedListingDoesNotPropagate() throws Exception {
        // This feeds a warning on a write that must not fail because the warning
        // could not be computed.
        when(agentFactory.getAllDeployedAgents(any())).thenThrow(new RuntimeException("registry unavailable"));

        var impact = analyzer.agentsLosingAccess(SECRET, List.of("agentOne"));

        assertTrue(impact.agentsLosingAccess().isEmpty());
        // The half that matters: an empty list from a failed scan must not read as
        // "nothing breaks", or the caller narrows the grant on the strength of it.
        assertFalse(impact.complete(), "a scan that could not list an environment is not complete");
    }

    @Test
    @DisplayName("one unlistable environment leaves the answer incomplete, with what was found in the other")
    void oneFailedEnvironmentIsStillIncomplete() throws Exception {
        IAgent inProduction = agent("agentTwo", 3, Deployment.Status.READY);
        when(agentFactory.getAllDeployedAgents(Deployment.Environment.production)).thenReturn(List.of(inProduction));
        when(agentFactory.getAllDeployedAgents(Deployment.Environment.test)).thenThrow(new RuntimeException("registry unavailable"));
        when(checker.references(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(true);

        var impact = analyzer.agentsLosingAccess(SECRET, List.of("agentOne"));

        assertEquals(List.of(new AffectedAgent("agentTwo", 3, "production")), impact.agentsLosingAccess());
        assertFalse(impact.complete());
    }

    @Test
    @DisplayName("a scan that reached every environment is complete, including the wildcard short-circuit")
    void successfulScansAreComplete() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));
        when(checker.references(any(), any(), any())).thenReturn(false);

        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).complete());
        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of("*")).complete());
        assertTrue(analyzer.agentsLosingAccess(null, List.of("agentOne")).complete());
    }

    @Test
    @DisplayName("a null secret yields no warning")
    void nullSecretMeansNoImpact() {
        assertTrue(analyzer.agentsLosingAccess(null, List.of("agentOne")).agentsLosingAccess().isEmpty());
        verifyNoInteractions(agentFactory);
    }

    @Test
    @DisplayName("an agent with no id is skipped rather than reported as a blank one")
    void skipsAgentsWithoutAnId() throws Exception {
        deployedInProduction(agent(null, 1, Deployment.Status.READY));
        when(checker.references(any(), any(), any())).thenReturn(true);

        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess().isEmpty());
    }
}
