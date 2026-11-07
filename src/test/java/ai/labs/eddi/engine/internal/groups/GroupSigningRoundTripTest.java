/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.SigningTestSupport;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.rest.RestAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent signing end to end, with nothing stubbed between the key and the
 * verdict: an admin provisions a key through the REST method, signing is
 * switched on through an ordinary agent save (which checks the key), the group
 * guard signs a transcript entry, and the same guard verifies it for a
 * receiving agent that requires peer verification. Real Ed25519 throughout,
 * over an in-memory vault and an in-memory agent store.
 * <p>
 * The guard returns no verdict yet — it logs — so the outcome is read from the
 * signing service's verify counters.
 */
class GroupSigningRoundTripTest {

    private static final String SPEAKER = "aabbccddee1122334455";
    private static final String RECEIVER = "bbccddeeff2233445566";
    private static final String GROUP_ID = "group-1";

    /** Every saved version of each agent, oldest first: version n is index n-1. */
    private final Map<String, List<AgentConfiguration>> agents = new HashMap<>();
    private SimpleMeterRegistry meterRegistry;
    private RestAgentStore restAgentStore;
    private GroupSigningGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        AgentSigningService signing = SigningTestSupport.signingService(SigningTestSupport.inMemoryVault(), meterRegistry);
        IAgentStore agentStore = inMemoryAgentStore();
        restAgentStore = new RestAgentStore(agentStore, mock(IRestWorkflowStore.class), mock(IDocumentDescriptorStore.class),
                mock(IJsonSchemaCreator.class), mock(IScheduleStore.class), mock(CapabilityRegistryService.class), mock(IDeploymentStore.class),
                mock(ResourceAccessGuard.class), signing, mock(IAgentFactory.class), "default", mock(AgentCascadeService.class));
        NonceCacheService nonceCache = mock(NonceCacheService.class);
        when(nonceCache.validate(anyString(), anyLong())).thenReturn(NonceCacheService.NonceValidation.VALID);
        guard = new GroupSigningGuard(agentStore, signing, nonceCache, "default");

        agents.put(SPEAKER, new ArrayList<>(List.of(agent())));
        var receiver = agent();
        var security = new AgentConfiguration.SecurityConfig();
        security.setRequirePeerVerification(true);
        receiver.setSecurity(security);
        agents.put(RECEIVER, new ArrayList<>(List.of(receiver)));
    }

    @Test
    void aProvisionedKeySignsAnEntryThatAPeerVerifies() throws Exception {
        provisionKeyAndEnableSigning();

        var signed = guard.signOutgoingMessage(SPEAKER, GROUP_ID, "the plan is sound", "OPINION");

        assertNotNull(signed.signature(), "signing is on and the key is usable, so the entry is signed");
        assertEquals(1, signed.keyVersion());
        double verifiedBefore = verified();
        guard.verifyPriorEntriesIfRequired(RECEIVER, conversation(entry("the plan is sound", signed)));
        assertEquals(verifiedBefore + 1, verified(), "the receiver verified the entry");
        assertEquals(0.0, failed());
    }

    @Test
    void aTamperedEntryFailsVerification() throws Exception {
        provisionKeyAndEnableSigning();
        var signed = guard.signOutgoingMessage(SPEAKER, GROUP_ID, "the plan is sound", "OPINION");

        double verifiedBefore = verified();
        guard.verifyPriorEntriesIfRequired(RECEIVER, conversation(entry("the plan is NOT sound", signed)));

        assertEquals(verifiedBefore, verified());
        assertEquals(1.0, failed(), "the altered content no longer matches the signature");
    }

    /**
     * After a rotation the new key signs, and an entry signed before it still
     * verifies against the version it declares.
     */
    @Test
    void afterARotationBothOldAndNewEntriesVerify() throws Exception {
        provisionKeyAndEnableSigning();
        var beforeRotation = guard.signOutgoingMessage(SPEAKER, GROUP_ID, "first", "OPINION");

        restAgentStore.createSigningKey(SPEAKER, currentVersion(SPEAKER));
        var afterRotation = guard.signOutgoingMessage(SPEAKER, GROUP_ID, "second", "OPINION");

        assertEquals(1, beforeRotation.keyVersion());
        assertEquals(2, afterRotation.keyVersion());
        double verifiedBefore = verified();
        guard.verifyPriorEntriesIfRequired(RECEIVER, conversation(entry("first", beforeRotation), entry("second", afterRotation)));
        assertEquals(verifiedBefore + 2, verified());
        assertEquals(0.0, failed());
    }

    /** Provisions through the REST method and turns signing on through a save. */
    private void provisionKeyAndEnableSigning() throws Exception {
        assertEquals(201, restAgentStore.createSigningKey(SPEAKER, currentVersion(SPEAKER)).getStatus());
        int version = currentVersion(SPEAKER);
        AgentConfiguration config = agents.get(SPEAKER).get(version - 1);
        var security = new AgentConfiguration.SecurityConfig();
        security.setSignInterAgentMessages(true);
        config.setSecurity(security);
        assertEquals(200, restAgentStore.updateAgent(SPEAKER, version, config, false).getStatus(),
                "the save checks the key and accepts it");
    }

    private double verified() {
        return meterRegistry.counter("eddi.agent.identity.verify.success").count();
    }

    private double failed() {
        return meterRegistry.counter("eddi.agent.identity.verify.fail").count();
    }

    private static TranscriptEntry entry(String content, GroupSigningGuard.SigningResult signed) {
        return new TranscriptEntry(SPEAKER, "Speaker", content, 0, "OPINION", TranscriptEntryType.OPINION, Instant.now(), null, null,
                signed.signature(), signed.nonce(), signed.timestampMs(), signed.keyVersion());
    }

    private static GroupConversation conversation(TranscriptEntry... entries) {
        var gc = new GroupConversation();
        gc.setId("gc-" + System.nanoTime());
        gc.setGroupId(GROUP_ID);
        gc.getTranscript().addAll(List.of(entries));
        return gc;
    }

    private static AgentConfiguration agent() {
        var config = new AgentConfiguration();
        config.setWorkflows(new ArrayList<>());
        return config;
    }

    private int currentVersion(String agentId) {
        return agents.get(agentId).size();
    }

    /**
     * Holds each agent's versions in {@link #agents}. An update must name the
     * current version, as the real store's optimistic lock demands.
     */
    private IAgentStore inMemoryAgentStore() throws Exception {
        IAgentStore store = mock(IAgentStore.class);
        when(store.getCurrentResourceId(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return agents.containsKey(id) ? resourceId(id, currentVersion(id)) : null;
        });
        when(store.read(anyString(), anyInt()))
                .thenAnswer(invocation -> agents.get(invocation.<String>getArgument(0)).get(invocation.<Integer>getArgument(1) - 1));
        when(store.update(anyString(), anyInt(), any(AgentConfiguration.class), anyBoolean())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if (invocation.<Integer>getArgument(1) != currentVersion(id)) {
                throw new IResourceStore.ResourceModifiedException("stale version");
            }
            agents.get(id).add(invocation.getArgument(2));
            return currentVersion(id);
        });
        return store;
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
