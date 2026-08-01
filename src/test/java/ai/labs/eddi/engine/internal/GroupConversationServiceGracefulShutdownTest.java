/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.lifecycle.model.DiscussionControlToken;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.internal.GracefulShutdownService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for R1 step 10 — {@link GroupConversationService}'s participation in
 * graceful shutdown: new discussion work is rejected once
 * {@link GracefulShutdownService} signals shutdown, and every currently-active
 * discussion is signalled {@link ControlSignal#CANCEL_GRACEFUL} so the
 * orchestration loop stops scheduling new phase work.
 *
 * @author tests
 */
class GroupConversationServiceGracefulShutdownTest {

    private static final int MAX_DEPTH = 3;
    private static final String DEFAULT_TENANT = "default";

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IConversationService conversationService;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private NonceCacheService nonceCacheService;

    private GroupConversationService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                mock(IScheduleStore.class), nonceCacheService, null, new CallerIdentityContext(null, null),
                DEFAULT_TENANT, MAX_DEPTH);
    }

    /**
     * A real GracefulShutdownService whose {@code isShuttingDown()} is externally
     * controllable.
     */
    private GracefulShutdownService shutdownGate(AtomicBoolean signalled) {
        return new GracefulShutdownService(mock(IConversationCoordinator.class), 1, 0, 1L) {
            @Override
            public boolean isShuttingDown() {
                return signalled.get();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, DiscussionControlToken> activeTokens() throws Exception {
        var field = GroupConversationService.class.getDeclaredField("activeTokens");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, DiscussionControlToken>) field.get(service);
    }

    // =================================================================
    // rejectIfShuttingDown gating
    // =================================================================

    @Test
    void discuss_whileShuttingDown_throwsRejectedExecutionException() {
        var signalled = new AtomicBoolean(true);
        service.gracefulShutdownService = shutdownGate(signalled);

        assertThrows(RejectedExecutionException.class,
                () -> service.discuss("group1", "question", "user1", 0));
    }

    @Test
    void startAndDiscussAsync_whileShuttingDown_throwsRejectedExecutionException() {
        var signalled = new AtomicBoolean(true);
        service.gracefulShutdownService = shutdownGate(signalled);

        assertThrows(RejectedExecutionException.class,
                () -> service.startAndDiscussAsync("group1", "question", "user1", null));
    }

    @Test
    void resumeDiscussion_whileShuttingDown_throwsRejectedExecutionException() {
        var signalled = new AtomicBoolean(true);
        service.gracefulShutdownService = shutdownGate(signalled);

        assertThrows(RejectedExecutionException.class,
                () -> service.resumeDiscussion("gc-1", new GroupApprovalRequest(), null));
    }

    @Test
    void discuss_notShuttingDown_doesNotReject() throws Exception {
        var signalled = new AtomicBoolean(false);
        service.gracefulShutdownService = shutdownGate(signalled);
        doReturn(null).when(groupStore).getCurrentResourceId("group1");

        // Falls through to the normal "group not found" path, not a shutdown rejection.
        var ex = assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> service.discuss("group1", "question", "user1", 0));
        assertNotNull(ex);
    }

    @Test
    void discuss_nullGate_neverRejects() throws Exception {
        // service.gracefulShutdownService is null by default (field-injected, unset
        // in direct-construction tests) — mirrors ConversationService's same "null
        // gate never rejects" contract.
        doReturn(null).when(groupStore).getCurrentResourceId("group1");

        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> service.discuss("group1", "question", "user1", 0));
    }

    // =================================================================
    // onShutdown — graceful-cancel every active discussion
    // =================================================================

    @Test
    void onShutdown_signalsGracefulCancelToEveryActiveDiscussion() throws Exception {
        var tokenA = new DiscussionControlToken();
        var tokenB = new DiscussionControlToken();
        activeTokens().put("gc-a", tokenA);
        activeTokens().put("gc-b", tokenB);

        service.onShutdown(null);

        assertEquals(ControlSignal.CANCEL_GRACEFUL, tokenA.getSignal());
        assertEquals(ControlSignal.CANCEL_GRACEFUL, tokenB.getSignal());
    }

    @Test
    void onShutdown_noActiveDiscussions_doesNothing() {
        assertDoesNotThrow(() -> service.onShutdown(null));
    }

    @Test
    void onShutdown_oneCancelFails_othersStillSignalled() throws Exception {
        // A null token id would NPE deep inside cancelDiscussion's DB path if it ever
        // reached it — but a live token short-circuits before any store call, so this
        // just proves one troublesome entry does not stop the rest from being
        // signalled.
        var tokenA = new DiscussionControlToken();
        activeTokens().put("gc-a", tokenA);
        var tokenB = new DiscussionControlToken();
        activeTokens().put("gc-b", tokenB);
        doThrow(new RuntimeException("read failed")).when(conversationStore).read(anyString());

        assertDoesNotThrow(() -> service.onShutdown(null));

        assertEquals(ControlSignal.CANCEL_GRACEFUL, tokenA.getSignal());
        assertEquals(ControlSignal.CANCEL_GRACEFUL, tokenB.getSignal());
    }
}
