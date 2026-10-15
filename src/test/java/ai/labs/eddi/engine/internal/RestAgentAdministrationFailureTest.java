/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.engine.model.DeploymentPreflight;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.Agent;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.secrets.VaultGrantChecker;
import ai.labs.eddi.secrets.VaultGrantGate;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The deploy response, the detailed status and the preflight all say WHY — and
 * none of them ever carries a secret value.
 */
@DisplayName("RestAgentAdministration — deployment failure reasons and preflight")
class RestAgentAdministrationFailureTest {

    private static final String AGENT_ID = "0123456789abcdef01234567";
    private static final String OTHER_AGENT = "aaaaaaaaaaaaaaaaaaaaaaaa";
    /**
     * Zero entropy, so no entropy-based scrubber can make the absence check
     * vacuous.
     */
    private static final String CHECKSUM = "0000000000000000";
    private static final Deployment.Environment ENV = Deployment.Environment.production;

    private IAgentFactory agentFactory;
    private ResourceAccessGuard guard;
    private VaultGrantChecker checker;
    private RestAgentAdministration admin;

    @BeforeEach
    void setUp() throws Exception {
        IRuntime runtime = mock(IRuntime.class);
        lenient().when(runtime.submitCallable(any(Callable.class), any())).thenReturn(CompletableFuture.completedFuture(null));
        agentFactory = mock(IAgentFactory.class);
        lenient().when(agentFactory.getAllLatestAgents(any())).thenReturn(List.of());
        IDeploymentStore deploymentStore = mock(IDeploymentStore.class);
        lenient().when(deploymentStore.readDeploymentInfos(any())).thenReturn(List.of());
        TenantQuotaService quota = mock(TenantQuotaService.class);
        lenient().when(quota.checkAgentQuota(any(), anyInt())).thenReturn(QuotaCheckResult.OK);
        guard = mock(ResourceAccessGuard.class);
        lenient().when(guard.hasAccess(any(), eq(AccessLevel.EDIT))).thenReturn(true);
        admin = new RestAgentAdministration(runtime, agentFactory, mock(IAgentStore.class), deploymentStore, mock(IConversationMemoryStore.class),
                mock(IRestConversationStore.class), mock(IDocumentDescriptorStore.class), mock(IDeploymentListener.class),
                mock(IScheduleStore.class), quota, guard);
        checker = mock(VaultGrantChecker.class);
        admin.vaultGrantGate = new VaultGrantGate(checker, "enforce");
    }

    private Agent refusedAgent() {
        var agent = new Agent(AGENT_ID, 1);
        agent.setDeploymentStatus(Deployment.Status.ERROR);
        agent.setDeploymentFailure(new VaultGrantGate.GrantCheck(VaultGrantGate.Mode.ENFORCE, List.of("${vault:gemini-api-key}"), true)
                .toFailure(AGENT_ID, 1));
        return agent;
    }

    @Nested
    @DisplayName("waited deploy")
    class WaitedDeploy {

        @Test
        @DisplayName("a refused deploy answers 200 with status ERROR, the failure, and its message as error")
        void refusedCarriesFailure() throws Exception {
            var agent = refusedAgent();
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);

            Response response = admin.deployAgent(ENV, AGENT_ID, 1, true, true);

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ERROR", body.get("status"));
            DeploymentFailure failure = (DeploymentFailure) body.get("failure");
            assertEquals(DeploymentFailure.VAULT_GRANT_MISSING, failure.code());
            assertEquals("gemini-api-key", failure.secrets().get(0).keyName());
            assertEquals(failure.message(), body.get("error"));
        }

        @Test
        @DisplayName("a READY deploy carries no failure")
        void readyHasNoFailure() throws Exception {
            var agent = new Agent(AGENT_ID, 1);
            agent.setDeploymentStatus(Deployment.Status.READY);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);

            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) admin.deployAgent(ENV, AGENT_ID, 1, true, true).getEntity();

            assertFalse(body.containsKey("failure"));
            assertFalse(body.containsKey("error"));
        }
    }

    @Nested
    @DisplayName("deployment status")
    class Status {

        @Test
        @DisplayName("format=detailed adds the failure for a caller with EDIT")
        void detailedHasFailure() throws Exception {
            var agent = refusedAgent();
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(agent);

            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) admin.getDeploymentStatus(ENV, AGENT_ID, 1, "detailed").getEntity();

            assertEquals("ERROR", body.get("status"));
            assertSame(agent.getDeploymentFailure(), body.get("failure"));
        }

        @Test
        @DisplayName("the plain JSON status stays as it was")
        void jsonUnchanged() throws Exception {
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(refusedAgent());

            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) admin.getDeploymentStatus(ENV, AGENT_ID, 1, "json").getEntity();

            assertEquals(Map.of("status", "ERROR"), body);
        }

        @Test
        @DisplayName("a caller without EDIT gets the status but not the failure")
        void noEditNoFailure() throws Exception {
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(refusedAgent());
            when(guard.hasAccess(AGENT_ID, AccessLevel.EDIT)).thenReturn(false);

            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) admin.getDeploymentStatus(ENV, AGENT_ID, 1, "detailed").getEntity();

            assertEquals(Map.of("status", "ERROR"), body);
        }
    }

    @Nested
    @DisplayName("preflight")
    class Preflight {

        private void restrictedTo(String... agents) {
            when(checker.findUngrantedReferences(AGENT_ID, 1)).thenReturn(List.of("${vault:gemini-api-key}"));
            when(checker.metadataOf(new SecretReference("default", "gemini-api-key")))
                    .thenReturn(new SecretMetadata("default", "gemini-api-key", null, null, null, CHECKSUM, "d", List.of(agents)));
        }

        @Test
        @DisplayName("not ready in enforce mode, with the grant shown to an admin")
        void notReadyAdminSeesIds() throws Exception {
            restrictedTo(OTHER_AGENT);
            when(guard.isAdmin()).thenReturn(true);

            DeploymentPreflight preflight = admin.preflightDeployment(ENV, AGENT_ID, 1);

            assertFalse(preflight.ready());
            assertTrue(preflight.checked());
            assertEquals("ENFORCE", preflight.enforcement());
            var issue = preflight.grantIssues().get(0);
            assertEquals("default", issue.tenantId());
            assertEquals("gemini-api-key", issue.keyName());
            assertEquals(1, issue.allowedAgentCount());
            assertEquals(List.of(OTHER_AGENT), issue.allowedAgents());
            assertFalse(issue.grantsAllAgents());
        }

        @Test
        @DisplayName("an editor gets the count, not the ids")
        void editorGetsCountOnly() throws Exception {
            restrictedTo(OTHER_AGENT, "cccccccccccccccccccccccc");
            when(guard.isAdmin()).thenReturn(false);

            var issue = admin.preflightDeployment(ENV, AGENT_ID, 1).grantIssues().get(0);

            assertEquals(2, issue.allowedAgentCount());
            assertNull(issue.allowedAgents());
        }

        @Test
        @DisplayName("ready when nothing is missing")
        void ready() throws Exception {
            when(checker.findUngrantedReferences(AGENT_ID, 1)).thenReturn(List.of());

            DeploymentPreflight preflight = admin.preflightDeployment(ENV, AGENT_ID, 1);

            assertTrue(preflight.ready());
            assertTrue(preflight.grantIssues().isEmpty());
        }

        @Test
        @DisplayName("warn mode lists the issue but is ready, exactly like the deploy")
        void warnIsReady() throws Exception {
            admin.vaultGrantGate = new VaultGrantGate(checker, "warn");
            restrictedTo(OTHER_AGENT);

            DeploymentPreflight preflight = admin.preflightDeployment(ENV, AGENT_ID, 1);

            assertTrue(preflight.ready());
            assertEquals(1, preflight.grantIssues().size());
        }

        @Test
        @DisplayName("EDIT is required, as for the deploy it previews")
        void requiresEdit() {
            doThrow(new ForbiddenException("no")).when(guard).requireAccess(AGENT_ID, AccessLevel.EDIT, "agent");
            assertThrows(ForbiddenException.class, () -> admin.preflightDeployment(ENV, AGENT_ID, 1));
        }

        @Test
        @DisplayName("no secret value or checksum appears anywhere in the serialised answer")
        void noValueInResponse() throws Exception {
            restrictedTo(OTHER_AGENT);
            when(guard.isAdmin()).thenReturn(true);

            String json = new ObjectMapper().writeValueAsString(admin.preflightDeployment(ENV, AGENT_ID, 1));

            assertFalse(json.contains(CHECKSUM), json);
            assertFalse(json.contains("\"value\""), json);
            assertFalse(json.contains("\"d\""), json);
        }
    }
}
