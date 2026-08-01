/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.SecurityConfig;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link GroupSigningGuard}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 3) refactor.
 * Covers the guard-clause branches directly; the full sign → self-verify →
 * nonce-validate happy path requires real Ed25519 key material and is exercised
 * by {@code AgentSigningService}'s own tests plus the characterization suites
 * this extraction preserved ({@code GroupConversationServiceHitlCoverage3Test},
 * {@code GroupConversationServiceBranchCoverageTest}).
 *
 * @author tests
 */
class GroupSigningGuardTest {

    @Mock
    private IAgentStore agentStore;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private NonceCacheService nonceCacheService;

    private static final String AGENT_A = "agent-a";
    private static final String GROUP_ID = "group-1";
    private static final String TENANT = "default";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private GroupSigningGuard guard() {
        return new GroupSigningGuard(agentStore, agentSigningService, nonceCacheService, TENANT);
    }

    // =================================================================
    // signOutgoingMessage — guard clauses, all resolve to UNSIGNED
    // =================================================================

    @Test
    void signOutgoingMessage_nullAgentStore_unsigned() {
        var guard = new GroupSigningGuard(null, agentSigningService, nonceCacheService, TENANT);
        var result = guard.signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");
        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void signOutgoingMessage_nullSigningService_unsigned() {
        var guard = new GroupSigningGuard(agentStore, null, nonceCacheService, TENANT);
        var result = guard.signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");
        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentStore);
    }

    @Test
    void signOutgoingMessage_nullNonceCache_unsigned() {
        var guard = new GroupSigningGuard(agentStore, agentSigningService, null, TENANT);
        var result = guard.signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");
        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
    }

    @Test
    void signOutgoingMessage_noSecurityConfig_unsigned() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        // security left null
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");

        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void signOutgoingMessage_signingDisabled_unsigned() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setSignInterAgentMessages(false);
        config.setSecurity(security);
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");

        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void signOutgoingMessage_nullResponse_unsigned() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setSignInterAgentMessages(true);
        config.setSecurity(security);
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, null, "OPINION");

        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
        verifyNoInteractions(agentSigningService);
    }

    @Test
    void signOutgoingMessage_storeThrows_unsignedNotPropagated() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_A)).thenThrow(new RuntimeException("store down"));

        var result = guard().signOutgoingMessage(AGENT_A, GROUP_ID, "hi", "OPINION");

        assertEquals(GroupSigningGuard.SigningResult.UNSIGNED, result);
    }

    // =================================================================
    // verifyPriorEntriesIfRequired — guard clauses
    // =================================================================

    @Test
    void verifyPriorEntriesIfRequired_nullAgentStore_noop() {
        var guard = new GroupSigningGuard(null, agentSigningService, nonceCacheService, TENANT);
        assertDoesNotThrow(() -> guard.verifyPriorEntriesIfRequired(AGENT_A, gc()));
    }

    @Test
    void verifyPriorEntriesIfRequired_resourceIdNull_noReadOfConfig() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(null);

        assertDoesNotThrow(() -> guard().verifyPriorEntriesIfRequired(AGENT_A, gc()));

        verify(agentStore, never()).read(eq(AGENT_A), anyInt());
    }

    @Test
    void verifyPriorEntriesIfRequired_storeThrows_swallowed() throws Exception {
        when(agentStore.getCurrentResourceId(AGENT_A)).thenThrow(new RuntimeException("store down"));

        assertDoesNotThrow(() -> guard().verifyPriorEntriesIfRequired(AGENT_A, gc()));
    }

    @Test
    void verifyPriorEntriesIfRequired_peerVerificationNotRequired_noop() throws Exception {
        var resId = mockResourceId();
        var config = new AgentConfiguration();
        var security = new SecurityConfig();
        security.setRequirePeerVerification(false);
        config.setSecurity(security);
        when(agentStore.getCurrentResourceId(AGENT_A)).thenReturn(resId);
        when(agentStore.read(AGENT_A, resId.getVersion())).thenReturn(config);

        assertDoesNotThrow(() -> guard().verifyPriorEntriesIfRequired(AGENT_A, gc()));

        verifyNoInteractions(agentSigningService);
    }

    // =================================================================
    // forgetConversation
    // =================================================================

    @Test
    void forgetConversation_unknownId_doesNotThrow() {
        assertDoesNotThrow(() -> guard().forgetConversation("never-registered"));
    }

    private GroupConversation gc() {
        var g = new GroupConversation();
        g.setId("gc-1");
        g.setGroupId(GROUP_ID);
        return g;
    }

    private ai.labs.eddi.datastore.IResourceStore.IResourceId mockResourceId() {
        return new ai.labs.eddi.datastore.IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return AGENT_A;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }
}
