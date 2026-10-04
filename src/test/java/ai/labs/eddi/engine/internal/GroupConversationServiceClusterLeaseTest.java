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
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseInfo;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cluster mode: the group lease {@code g.<id>} a running discussion holds on
 * its node, and the cancel that another node forwards to that holder.
 */
@DisplayName("GroupConversationService — group lease and forwarded cancel in cluster mode")
class GroupConversationServiceClusterLeaseTest {

    private static final String GC_ID = "gc-cluster";

    private IGroupConversationStore conversationStore;
    private IConversationLeaseManager leaseManager;
    private IClusterRpc clusterRpc;
    private GroupConversationService service;

    private final List<LogRecord> warnings = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                warnings.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger julLogger = Logger.getLogger(GroupConversationService.class.getName());
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        conversationStore = mock(IGroupConversationStore.class);
        service = new GroupConversationService(mock(IAgentGroupStore.class), conversationStore, mock(IConversationService.class),
                mock(IAgentFactory.class), mock(ITemplatingEngine.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                mock(AgentSigningService.class), mock(IAgentStore.class), mock(IScheduleStore.class), mock(NonceCacheService.class), null,
                new CallerIdentityContext(null, null), "default", 3);
        leaseManager = mock(IConversationLeaseManager.class);
        when(leaseManager.isClustered()).thenReturn(true);
        clusterRpc = mock(IClusterRpc.class);
        when(clusterRpc.isClustered()).thenReturn(true);
        service.leaseManager = leaseManager;
        service.clusterRpc = clusterRpc;
        service.nodeIdentity = new NodeIdentity("node-a", "boot-a");
        // logging.properties turns ai.labs.eddi OFF for unit tests: open this logger
        // explicitly.
        previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(capture);
    }

    @AfterEach
    void tearDown() {
        julLogger.removeHandler(capture);
        julLogger.setLevel(previousLevel);
    }

    private static String message(LogRecord record) {
        Object[] params = record.getParameters();
        String text = String.valueOf(record.getMessage());
        return params == null || params.length == 0 ? text : String.format(text, params);
    }

    @Test
    @DisplayName("a group lease held elsewhere is logged, the discussion goes on, and a later lease is still taken and released")
    void failedAcquisitionIsLoggedAndDoesNotWedge() {
        when(leaseManager.tryAcquireKey(IConversationLeaseManager.GROUP + GC_ID)).thenReturn(Optional.empty());

        service.holdGroupLease(GC_ID);
        service.releaseGroupLease(GC_ID);

        assertTrue(warnings.stream().anyMatch(r -> message(r).contains(GC_ID) && message(r).contains("without its group lease")),
                "a discussion running without its lease must say so: "
                        + warnings.stream().map(GroupConversationServiceClusterLeaseTest::message).toList());
        verify(leaseManager, never()).release(any());

        LeaseHandle lease = mock(LeaseHandle.class);
        when(leaseManager.tryAcquireKey(IConversationLeaseManager.GROUP + GC_ID)).thenReturn(Optional.of(lease));
        service.holdGroupLease(GC_ID);
        service.releaseGroupLease(GC_ID);
        verify(leaseManager).release(lease);
    }

    @Test
    @DisplayName("a cancel forwarded to a holder that answers 'not running here' falls back to the database cancel")
    void forwardedCancelAnsweredFalseFallsBackToTheDatabase() throws Exception {
        when(leaseManager.peekKey(IConversationLeaseManager.GROUP + GC_ID)).thenReturn(Optional.of(new LeaseInfo("node-b", "boot-b", 7, 0)));
        when(clusterRpc.call(eq("node-b"), eq(IClusterRpc.GROUP_CONTROL), anyMap())).thenReturn(Optional.of(Map.of("cancelled", false)));
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        doReturn(gc).when(conversationStore).read(GC_ID);

        service.cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL);

        verify(clusterRpc).call(eq("node-b"), eq(IClusterRpc.GROUP_CONTROL), anyMap());
        verify(conversationStore).updateIfState(gc, GroupConversationState.AWAITING_APPROVAL);
        assertEquals(GroupConversationState.CANCELLED, gc.getState(), "the stale holder did not cancel it, so the database path must");
    }

    @Test
    @DisplayName("a cancel the holder performed is not applied a second time through the database")
    void forwardedCancelAnsweredTrueStaysRemote() throws Exception {
        when(leaseManager.peekKey(IConversationLeaseManager.GROUP + GC_ID)).thenReturn(Optional.of(new LeaseInfo("node-b", "boot-b", 7, 0)));
        when(clusterRpc.call(anyString(), anyString(), anyMap())).thenReturn(Optional.of(Map.of("cancelled", true)));

        assertTrue(service.cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL));
        verify(conversationStore, never()).read(anyString());
    }
}
