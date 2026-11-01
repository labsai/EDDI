/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.secrets.VaultGrantChecker.ReferenceCheck;
import ai.labs.eddi.secrets.VaultGrantImpactAnalyzer.AffectedAgent;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private IDeploymentStore deploymentStore;
    private VaultGrantImpactAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        checker = mock(VaultGrantChecker.class);
        agentFactory = mock(IAgentFactory.class);
        when(agentFactory.getAllDeployedAgents(any())).thenReturn(List.of());
        deploymentStore = mock(IDeploymentStore.class);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
        analyzer = new VaultGrantImpactAnalyzer(checker, agentFactory, deploymentStore);
    }

    /** A deployment recorded in the shared database — possibly on another node. */
    private static DeploymentInfo recorded(String id, Integer version, Deployment.Environment environment) {
        DeploymentInfo info = new DeploymentInfo();
        info.setAgentId(id);
        info.setAgentVersion(version);
        info.setEnvironment(environment);
        info.setDeploymentStatus(DeploymentInfo.DeploymentStatus.deployed);
        return info;
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
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

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
        verify(checker, never()).checkReferences(any(), any(), any());
    }

    @Test
    @DisplayName("an agent that does not reference the secret is not reported")
    void skipsAgentsThatDoNotUseTheSecret() throws Exception {
        deployedInProduction(agent("agentThree", 2, Deployment.Status.READY));
        when(checker.checkReferences(eq("agentThree"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.DOES_NOT_REFERENCE);

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
    @DisplayName("an agent whose deployment failed (ERROR) is not reported")
    void skipsAgentsThatAreNotReady() throws Exception {
        // A deployment that never came up is not a deployment this change breaks.
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.ERROR));
        when(checker.checkReferences(any(), any(), any())).thenReturn(ReferenceCheck.REFERENCES);

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
        when(checker.checkReferences(any(), any(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

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
        when(checker.checkReferences(eq("agentTwo"), eq(2), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        assertEquals(List.of(new AffectedAgent("agentTwo", 2, "production")),
                analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess());
    }

    @Test
    @DisplayName("every READY version is reported, and each only once")
    void reportsEachReadyVersion() throws Exception {
        IAgent v1 = agent("agentTwo", 1, Deployment.Status.READY);
        IAgent v2 = agent("agentTwo", 2, Deployment.Status.READY);
        deployedInProduction(v1, v2, v2);
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

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
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        var impact = analyzer.agentsLosingAccess(SECRET, List.of("agentOne"));

        assertEquals(List.of(new AffectedAgent("agentTwo", 3, "production")), impact.agentsLosingAccess());
        assertFalse(impact.complete());
    }

    @Test
    @DisplayName("a scan that reached every environment is complete, including the wildcard short-circuit")
    void successfulScansAreComplete() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));
        when(checker.checkReferences(any(), any(), any())).thenReturn(ReferenceCheck.DOES_NOT_REFERENCE);

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
        when(checker.checkReferences(any(), any(), any())).thenReturn(ReferenceCheck.REFERENCES);

        assertTrue(analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess().isEmpty());
    }

    /**
     * S7: an agent whose configuration could not be read used to count as "does not
     * reference the secret", and the scan still reported {@code complete=true} —
     * telling an operator that narrowing the grant breaks nothing, on the strength
     * of a config nobody had read.
     */
    @Test
    @DisplayName("an agent whose configuration cannot be read makes the answer incomplete")
    void unreadableAgentMakesTheScanIncomplete() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY), agent("agentThree", 1, Deployment.Status.READY));
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.UNKNOWN);
        when(checker.checkReferences(eq("agentThree"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        var impact = analyzer.agentsLosingAccess(SECRET, List.of("agentOne"));

        assertFalse(impact.complete());
        // The readable agent is still reported; the unreadable one is not guessed at.
        assertEquals(List.of(new AffectedAgent("agentThree", 1, "production")), impact.agentsLosingAccess());
    }

    // ─── creating a secret that deployed agents already reference ───

    /**
     * The deploy gate lets a reference to an absent secret through (agent sync
     * deploys before the target holds the secret). Creating it later with a grant
     * that leaves such an agent out is the grant the gate would have refused.
     */
    @Test
    @DisplayName("enforce: creating a secret whose grant leaves out a deployed agent referencing it is refused, naming the agent")
    void creationLeavingOutAReferencingAgentIsRefused() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        String refusal = analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.ENFORCE);

        assertNotNull(refusal);
        assertTrue(refusal.contains("agentTwo"), refusal);
        assertTrue(refusal.contains(SECRET.toReferenceString()), refusal);
    }

    @Test
    @DisplayName("creating a secret is allowed when its grant names every referencing agent, or is the wildcard")
    void creationNamingTheReferencingAgentsIsAllowed() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        assertNull(analyzer.refusalToCreate(SECRET, List.of("agentOne", "agentTwo"), VaultGrantGate.Mode.ENFORCE));
        assertNull(analyzer.refusalToCreate(SECRET, List.of("*"), VaultGrantGate.Mode.ENFORCE));
    }

    @Test
    @DisplayName("warn and off allow the creation; off does not even scan")
    void creationFollowsTheEnforcementMode() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        assertNull(analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.WARN));
        verify(checker).checkReferences(eq("agentTwo"), anyInt(), eq(SECRET));

        assertNull(analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.OFF));
        verify(checker).checkReferences(eq("agentTwo"), anyInt(), eq(SECRET));
    }

    /**
     * An empty list from a scan that could not read an agent is "not known", not
     * "nothing references it". Letting the creation through would hand the value to
     * that agent, if it does reference the secret, for as long as it stays
     * deployed; refusing costs a retry.
     */
    @Test
    @DisplayName("enforce: an agent whose configuration cannot be read blocks the creation, and is named")
    void unreadableAgentBlocksCreation() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.UNKNOWN);

        String refusal = analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.ENFORCE);

        assertNotNull(refusal);
        assertTrue(refusal.contains("agentTwo"), refusal);
        assertTrue(refusal.contains("could not be read"), refusal);
        // The way out it names: the agent on the grant needs no scan.
        assertNull(analyzer.refusalToCreate(SECRET, List.of("agentOne", "agentTwo"), VaultGrantGate.Mode.ENFORCE));
        assertNull(analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.WARN));
    }

    @Test
    @DisplayName("enforce: a registry that cannot be listed blocks the creation")
    void failedListingBlocksCreation() throws Exception {
        when(agentFactory.getAllDeployedAgents(any())).thenThrow(new RuntimeException("registry unavailable"));

        String refusal = analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.ENFORCE);

        assertNotNull(refusal);
        assertTrue(refusal.contains("could not all be listed"), refusal);
        assertNull(analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.WARN));
        assertNull(analyzer.refusalToCreate(SECRET, List.of("*"), VaultGrantGate.Mode.ENFORCE));
    }

    // ─── the fleet, not only this node ───

    /**
     * The runtime registry is per node, the vault is shared. An agent deployed on
     * another node shows up only in the deployment records.
     */
    @Test
    @DisplayName("an agent deployed only on another node is found through the deployment records")
    void findsAgentsDeployedOnOtherNodes() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(recorded("agentTwo", 3, Deployment.Environment.production)));
        when(checker.checkReferences(eq("agentTwo"), eq(3), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        assertEquals(List.of(new AffectedAgent("agentTwo", 3, "production")),
                analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess());
        String refusal = analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.ENFORCE);
        assertNotNull(refusal);
        assertTrue(refusal.contains("agentTwo"), refusal);
    }

    @Test
    @DisplayName("an agent both running here and recorded is checked and reported once")
    void runtimeAndRecordAreOneAgent() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.READY));
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed))
                .thenReturn(List.of(recorded("agentTwo", 3, Deployment.Environment.production)));
        when(checker.checkReferences(eq("agentTwo"), eq(3), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        assertEquals(List.of(new AffectedAgent("agentTwo", 3, "production")),
                analyzer.agentsLosingAccess(SECRET, List.of("agentOne")).agentsLosingAccess());
        verify(checker).checkReferences(eq("agentTwo"), eq(3), eq(SECRET));
    }

    @Test
    @DisplayName("deployment records that cannot be read leave the answer incomplete, and block an enforced creation")
    void unreadableRecordsAreIncomplete() throws Exception {
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenThrow(new RuntimeException("db down"));

        var impact = analyzer.agentsLosingAccess(SECRET, List.of("agentOne"));

        assertTrue(impact.agentsLosingAccess().isEmpty());
        assertFalse(impact.complete());
        assertNotNull(analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.ENFORCE));
    }

    @Test
    @DisplayName("an agent still coming up counts: the deploy gate already let it through")
    void agentInProgressCounts() throws Exception {
        deployedInProduction(agent("agentTwo", 3, Deployment.Status.IN_PROGRESS));
        when(checker.checkReferences(eq("agentTwo"), anyInt(), eq(SECRET))).thenReturn(ReferenceCheck.REFERENCES);

        assertNotNull(analyzer.refusalToCreate(SECRET, List.of("agentOne"), VaultGrantGate.Mode.ENFORCE));
    }
}
