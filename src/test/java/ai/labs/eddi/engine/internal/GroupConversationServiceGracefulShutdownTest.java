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

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for R1 step 10 — {@link GroupConversationService}'s participation in
 * graceful shutdown: once {@link GracefulShutdownService} signals shutdown,
 * every entry point that starts, continues or resumes a discussion refuses new
 * work with {@link RejectedExecutionException} (mapped to HTTP 503 by
 * {@code RejectedExecutionExceptionMapper}) rather than admitting work the node
 * is about to abandon.
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

    /**
     * {@code continueDiscussion} re-runs every phase from index 0 and mutates
     * persisted state (CAS {@code COMPLETED → IN_PROGRESS}, round bump, transcript
     * append) BEFORE any agent work. Ungated during a drain, each member turn would
     * be refused by {@code ConversationService.say}'s own gate and recorded as
     * {@code SKIPPED}, leaving a healthy COMPLETED conversation back at COMPLETED
     * but with a round of skipped entries and a stale synthesized answer. The
     * rejection must therefore happen before the first CAS.
     */
    @Test
    void continueDiscussion_whileShuttingDown_throwsBeforeMutatingState() throws Exception {
        var signalled = new AtomicBoolean(true);
        service.gracefulShutdownService = shutdownGate(signalled);

        assertThrows(RejectedExecutionException.class,
                () -> service.continueDiscussion("gc-1", "follow-up question", null));

        // Nothing was read, no CAS attempted — the conversation is untouched.
        verifyNoInteractions(conversationStore);
    }

    /**
     * Same reasoning as {@code continueDiscussion}: {@code followUpWithMember}
     * CASes {@code COMPLETED → IN_PROGRESS} and appends to the transcript before
     * calling the agent.
     */
    @Test
    void followUpWithMember_whileShuttingDown_throwsBeforeMutatingState() throws Exception {
        var signalled = new AtomicBoolean(true);
        service.gracefulShutdownService = shutdownGate(signalled);

        assertThrows(RejectedExecutionException.class,
                () -> service.followUpWithMember("gc-1", "agent-a", "follow-up question"));

        verifyNoInteractions(conversationStore);
    }
}
