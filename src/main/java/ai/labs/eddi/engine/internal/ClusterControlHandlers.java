/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.cluster.ClusterStartable;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the node-addressed control operations of cluster mode — what another
 * node asks of the node where a turn, a discussion or a user's work is actually
 * running:
 * <ul>
 * <li>{@code conversation-cancel}: signal the running turn of a conversation
 * (cancel and end issued on another node)</li>
 * <li>{@code group-control}: cancel a group discussion that runs here</li>
 * <li>{@code gdpr-stop}: stop the in-flight work of an erased user, who arrives
 * as a hash</li>
 * <li>{@code coordinator-status}: this node's queue depths, for the admin
 * cluster view</li>
 * </ul>
 * Started only in cluster mode ({@link ClusterStartable}).
 */
@ApplicationScoped
public class ClusterControlHandlers implements ClusterStartable {

    private static final Logger LOGGER = Logger.getLogger(ClusterControlHandlers.class);

    private final IClusterRpc rpc;
    private final ConversationService conversationService;
    private final GroupConversationService groupConversationService;
    private final Instance<UserErasureParticipant> erasureParticipants;
    private final IConversationCoordinator coordinator;

    @Inject
    public ClusterControlHandlers(IClusterRpc rpc, ConversationService conversationService, GroupConversationService groupConversationService,
            Instance<UserErasureParticipant> erasureParticipants, IConversationCoordinator coordinator) {
        this.rpc = rpc;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.erasureParticipants = erasureParticipants;
        this.coordinator = coordinator;
    }

    @Override
    public void startCluster() {
        rpc.handle(IClusterRpc.CONVERSATION_CANCEL,
                request -> Map.of("signalled", conversationService.signalLocalInFlight(String.valueOf(request.get("conversationId")))));
        rpc.handle(IClusterRpc.GROUP_CONTROL, this::groupControl);
        rpc.handle(IClusterRpc.GDPR_STOP, this::gdprStop);
        rpc.handle(IClusterRpc.COORDINATOR_STATUS, request -> {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("queueDepths", coordinator.getQueueDepths());
            reply.put("totalProcessed", coordinator.getTotalProcessed());
            return reply;
        });
    }

    private Map<String, Object> groupControl(Map<String, Object> request) {
        String id = String.valueOf(request.get("groupConversationId"));
        if (!"cancel".equals(request.get("op"))) {
            return Map.of("error", "unsupported op " + request.get("op"));
        }
        ControlSignal mode;
        try {
            mode = ControlSignal.valueOf(String.valueOf(request.get("mode")));
        } catch (IllegalArgumentException e) {
            mode = ControlSignal.CANCEL_GRACEFUL;
        }
        try {
            return Map.of("cancelled", groupConversationService.cancelLocalDiscussion(id, mode));
        } catch (Exception e) {
            return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, Object> gdprStop(Map<String, Object> request) {
        String userIdHash = String.valueOf(request.get("userIdHash"));
        int stopped = 0;
        List<String> failed = new ArrayList<>();
        for (UserErasureParticipant participant : erasureParticipants) {
            try {
                stopped += participant.stopInFlightWorkByHash(userIdHash);
            } catch (RuntimeException e) {
                LOGGER.warnf("GDPR stop step %s failed for a remote erasure: %s", participant.erasureStepName(), e.getMessage());
                failed.add(participant.erasureStepName());
            }
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("stopped", stopped);
        if (!failed.isEmpty()) {
            // Reported, so the erasing node records its cluster stop as incomplete.
            reply.put("error", "stop step(s) failed: " + String.join(", ", failed));
        }
        return reply;
    }
}
