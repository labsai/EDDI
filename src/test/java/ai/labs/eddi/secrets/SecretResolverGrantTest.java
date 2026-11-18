/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.RecordingEventBus;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agent grant enforced where a secret is resolved (#1179).
 */
class SecretResolverGrantTest {

    private static final String AGENT_A = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final SecretReference KEY = new SecretReference("default", "openai-key");
    private static final String REF = "${vault:openai-key}";

    private ISecretProvider provider;
    private SimpleMeterRegistry meters;
    private SecretResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        provider = mock(ISecretProvider.class);
        meters = new SimpleMeterRegistry();
        when(provider.isAvailable()).thenReturn(true);
        when(provider.resolve(KEY)).thenReturn("sk-plaintext");
        grant(List.of(AGENT_A));
        resolver = resolver("enforce");
    }

    private SecretResolver resolver(String mode) {
        var r = new SecretResolver(provider, meters, 5, 100, mode);
        r.init();
        return r;
    }

    private void grant(List<String> allowedAgents) throws Exception {
        when(provider.getMetadata(KEY)).thenReturn(new SecretMetadata("default", "openai-key", Instant.now(), null, null, "c", null, allowedAgents));
    }

    private double refusals() {
        return meters.counter("eddi.vault.grant.refusals").count();
    }

    @Nested
    @DisplayName("the two cluster gaps of the deploy-time scan")
    class ClusterGaps {

        /**
         * Neither gap can be reproduced in a unit test, and neither needs to be: the
         * resolver reads no deployment record and no runtime registry, so what any scan
         * saw cannot matter to it. These tests pin that down: a scan that reports a
         * complete, empty answer, and a refusal anyway.
         * <p>
         * The scan sees no deployment of agent B — B passed the deploy gate on another
         * node after the scan read the records, or was deployed there with
         * autoDeploy=false and so has no record — and reports a complete, empty answer.
         * The resolver refuses B anyway, because it never asks the deployments.
         */
        private void assertScanMissesB() {
            IAgentFactory factory = mock(IAgentFactory.class);
            IDeploymentStore records = mock(IDeploymentStore.class);
            try {
                when(factory.getAllDeployedAgents(any())).thenReturn(List.of());
                when(records.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            var scan = new VaultGrantImpactAnalyzer(mock(VaultGrantChecker.class), factory, records).agentsLosingAccess(KEY, List.of(AGENT_A));
            assertTrue(scan.complete(), "every source was read");
            assertTrue(scan.agentsLosingAccess().isEmpty(), "and still B is not in it: complete() is not 'every deployment'");
        }

        @Test
        @DisplayName("a deployment that passed the gate on another node after the scan cannot resolve the secret")
        void deploymentRacingTheScanIsRefused() {
            assertScanMissesB();
            var e = assertThrows(SecretResolver.SecretNotGrantedException.class, () -> resolver.resolveValue(REF, SecretAccess.agent(AGENT_B)));
            assertTrue(e.getMessage().contains(AGENT_B), e.getMessage());
            assertTrue(e.getMessage().contains("/grant/agents/" + AGENT_B), "names the fix: " + e.getMessage());
            assertTrue(!e.getMessage().contains("sk-plaintext"), "never the value");
        }

        @Test
        @DisplayName("an autoDeploy=false deployment on another node cannot resolve the secret")
        void unrecordedDeploymentIsRefused() throws Exception {
            assertScanMissesB();
            assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> resolver.resolveSecrets(Map.of("apiKey", REF, "model", "gpt"), SecretAccess.agent(AGENT_B)));
            verify(provider, never()).resolve(KEY);
            assertEquals(1.0, refusals());
        }
    }

    @Nested
    @DisplayName("the cache")
    class Cache {

        @Test
        @DisplayName("a value cached for a granted agent is not handed to an agent the grant leaves out")
        void cacheHitIsCheckedPerAgent() throws Exception {
            assertEquals("sk-plaintext", resolver.resolveValue(REF, SecretAccess.agent(AGENT_A)));
            assertEquals("sk-plaintext", resolver.resolveValue(REF, SecretAccess.agent(AGENT_A)));
            assertThrows(SecretResolver.SecretNotGrantedException.class, () -> resolver.resolveValue(REF, SecretAccess.agent(AGENT_B)));
            verify(provider, times(1)).resolve(KEY);
            assertEquals(1.0, meters.counter("eddi.vault.cache.hits").count(), "the second A read was a hit, B never reached the cache");
        }

        @Test
        @DisplayName("a narrowed grant takes effect at the next resolution, cached value or not")
        void narrowedGrantCutsOffACachedAgent() throws Exception {
            grant(List.of(AGENT_A, AGENT_B));
            assertEquals("sk-plaintext", resolver.resolveValue(REF, SecretAccess.agent(AGENT_B)));
            grant(List.of(AGENT_A));
            resolver.invalidateCache(KEY); // what every grant write does
            assertThrows(SecretResolver.SecretNotGrantedException.class, () -> resolver.resolveValue(REF, SecretAccess.agent(AGENT_B)));
        }

        @Test
        @DisplayName("the grant is cached too: a hit reads no metadata")
        void grantIsCached() throws Exception {
            resolver.resolveValue(REF, SecretAccess.agent(AGENT_A));
            resolver.resolveValue(REF, SecretAccess.agent(AGENT_A));
            verify(provider, times(1)).getMetadata(KEY);
        }

        @Test
        @DisplayName("a grant read that races a grant write is not kept: the next resolution reads the grant again")
        void grantReadRacingAWriteIsNotCached() throws Exception {
            when(provider.getMetadata(KEY)).thenAnswer(invocation -> {
                // The write lands while this node is still reading the old grant.
                resolver.invalidateCache(KEY);
                return new SecretMetadata("default", "openai-key", Instant.now(), null, null, "c", null, List.of(AGENT_A, AGENT_B));
            });
            resolver.resolveValue(REF, SecretAccess.agent(AGENT_B));
            grant(List.of(AGENT_A));
            assertThrows(SecretResolver.SecretNotGrantedException.class, () -> resolver.resolveValue(REF, SecretAccess.agent(AGENT_B)),
                    "the stale grant read during the write must not have been cached");
        }

        @Test
        @DisplayName("a grant changed on another node reaches this node's grant cache through the cluster event")
        void clusterEventEvictsTheGrant() throws Exception {
            var bus = new RecordingEventBus();
            var clustered = new SecretResolver(provider, meters, 5, 100, "enforce");
            clustered.clusterEvents = bus;
            clustered.init();
            grant(List.of(AGENT_A, AGENT_B));
            clustered.resolveValue(REF, SecretAccess.agent(AGENT_B));
            grant(List.of(AGENT_A));
            bus.deliver(ClusterEvent.SECRET_CHANGED, Map.of("tenantId", "default", "keyName", "openai-key"));
            assertThrows(SecretResolver.SecretNotGrantedException.class, () -> clustered.resolveValue(REF, SecretAccess.agent(AGENT_B)));
        }
    }

    @Nested
    @DisplayName("who asks")
    class Accesses {

        @Test
        void agentWithoutIdOnlyResolvesSecretsOpenToEveryAgent() throws Exception {
            assertThrows(SecretResolver.SecretNotGrantedException.class, () -> resolver.resolveValue(REF, SecretAccess.agent(null)));
            assertThrows(SecretResolver.SecretNotGrantedException.class, () -> resolver.resolveValue(REF, null));
            grant(List.of("*"));
            resolver.invalidateLocal(KEY);
            assertEquals("sk-plaintext", resolver.resolveValue(REF, SecretAccess.agent(null)));
        }

        @Test
        void withoutAgentNeedsTheWildcard() throws Exception {
            var e = assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> resolver.resolveValue(REF, SecretAccess.withoutAgent("knowledge base ingestion")));
            assertTrue(e.getMessage().contains("[\"*\"]"), e.getMessage());
            grant(null); // a legacy secret with no list is open
            resolver.invalidateLocal(KEY);
            assertEquals("sk-plaintext", resolver.resolveValue(REF, SecretAccess.withoutAgent("knowledge base ingestion")));
        }

        @Test
        void outsideAgentGrantsDoesNotReadTheGrant() throws Exception {
            assertEquals("sk-plaintext", resolver.resolveValue(REF, SecretAccess.outsideAgentGrants("outbound proxy")));
            verify(provider, never()).getMetadata(KEY);
        }

        @Test
        void grantedAgentResolves() {
            assertEquals("Bearer sk-plaintext", resolver.resolveValue("Bearer " + REF, SecretAccess.agent(AGENT_A)));
        }
    }

    @Nested
    @DisplayName("failure modes")
    class Failures {

        @Test
        @DisplayName("an unreadable grant refuses: fail closed")
        void unreadableGrantRefuses() throws Exception {
            when(provider.getMetadata(KEY)).thenThrow(new ISecretProvider.SecretProviderException("db down"));
            var e = assertThrows(SecretResolver.SecretNotGrantedException.class, () -> resolver.resolveValue(REF, SecretAccess.agent(AGENT_A)));
            assertTrue(e.getMessage().contains("could not be read"), e.getMessage());
            verify(provider, never()).resolve(KEY);
        }

        @Test
        @DisplayName("a missing secret is left unresolved, as before")
        void missingSecretPassesThrough() throws Exception {
            when(provider.getMetadata(KEY)).thenThrow(new ISecretProvider.SecretNotFoundException("gone"));
            assertEquals(REF, resolver.resolveValue(REF, SecretAccess.agent(AGENT_A)));
        }

        @Test
        void warnModeLogsAndResolves() throws Exception {
            var warn = resolver("warn");
            assertEquals("sk-plaintext", warn.resolveValue(REF, SecretAccess.agent(AGENT_B)));
            assertEquals(0.0, refusals());
        }

        @Test
        void offModeReadsNoGrant() throws Exception {
            var off = resolver("off");
            assertEquals("sk-plaintext", off.resolveValue(REF, SecretAccess.agent(AGENT_B)));
            off.requireGranted(List.of(REF), SecretAccess.agent(AGENT_B), "test");
            verify(provider, never()).getMetadata(KEY);
        }

        @Test
        void unknownModeFailsConstruction() {
            assertThrows(IllegalArgumentException.class, () -> new SecretResolver(provider, meters, 5, 100, "enforced"));
        }
    }

    @Nested
    @DisplayName("requireGranted, for callers with a cache of their own")
    class RequireGranted {

        @Test
        void refusesWithoutResolvingAnything() throws Exception {
            assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> resolver.requireGranted(Map.of("apiKey", REF), SecretAccess.agent(AGENT_B), "LLM provider 'openai'"));
            verify(provider, never()).resolve(KEY);
        }

        @Test
        void admitsTheGrantedAgent() {
            assertDoesNotThrow(() -> resolver.requireGranted(Map.of("apiKey", REF), SecretAccess.agent(AGENT_A), "LLM provider 'openai'"));
        }

        @Test
        @DisplayName("a missing secret is refused, as a configuration error: a cached client may still carry its old value")
        void missingSecretRefused() throws Exception {
            when(provider.getMetadata(KEY)).thenThrow(new ISecretProvider.SecretNotFoundException("gone"));
            var e = assertThrows(SecretResolver.SecretMissingException.class,
                    () -> resolver.requireGranted(List.of(REF), SecretAccess.agent(AGENT_A), "MCP server 'x'"));
            assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
            assertEquals(0.0, refusals(), "a missing secret is not a grant refusal");
        }

        @Test
        @DisplayName("warn mode admits an agent the grant leaves out, without counting a refusal")
        void warnModeAdmits() {
            var warn = resolver("warn");
            assertDoesNotThrow(() -> warn.requireGranted(List.of(REF), SecretAccess.agent(AGENT_B), "x"));
            assertEquals(0.0, refusals());
        }

        @Test
        @DisplayName("warn mode still refuses a missing secret, which has nothing to admit")
        void warnModeRefusesAMissingSecret() throws Exception {
            when(provider.getMetadata(KEY)).thenThrow(new ISecretProvider.SecretNotFoundException("gone"));
            var warn = resolver("warn");
            assertThrows(SecretResolver.SecretMissingException.class, () -> warn.requireGranted(List.of(REF), SecretAccess.agent(AGENT_A), "x"));
            assertEquals(0.0, refusals());
        }

        @Test
        @DisplayName("a refusal is a SecretNotGrantedException but not a SecretMissingException, and is counted")
        void refusalIsCounted() {
            var e = assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> resolver.requireGranted(List.of(REF), SecretAccess.agent(AGENT_B), "x"));
            assertFalse(e instanceof SecretResolver.SecretMissingException);
            assertEquals(1.0, refusals());
        }

        @Test
        void reservedNamesAndPlainValuesAreSkipped() throws Exception {
            assertDoesNotThrow(() -> resolver.requireGranted(List.of("plain", "${vault:" + SecretReference.AGENT_SIGNING_KEY_PREFIX + "x}"),
                    SecretAccess.agent(AGENT_B), "x"));
        }

        @Test
        void vaultNotConfiguredDoesNothing() throws Exception {
            when(provider.isAvailable()).thenReturn(false);
            assertDoesNotThrow(() -> resolver.requireGranted(List.of(REF), SecretAccess.agent(AGENT_B), "x"));
        }
    }
}
