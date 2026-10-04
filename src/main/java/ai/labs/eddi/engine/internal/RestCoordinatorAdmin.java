/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.WebApplicationException;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.cluster.rpc.IClusterRpc;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IRestCoordinatorAdmin;
import ai.labs.eddi.engine.model.CoordinatorStatus;
import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * REST implementation for coordinator monitoring and dead-letter
 * administration.
 *
 * <p>
 * Delegates to the active {@link IConversationCoordinator} (in-memory or NATS).
 * The SSE endpoint polls coordinator status every 2 seconds and pushes updates
 * to connected clients.
 * </p>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestCoordinatorAdmin implements IRestCoordinatorAdmin {

    private static final Logger log = Logger.getLogger(RestCoordinatorAdmin.class);

    private final IConversationCoordinator coordinator;

    /** Connected SSE clients for broadcast */
    private final Set<SseEventSink> sseClients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "coordinator-sse-poller");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean pollerStarted = false;

    @Inject
    public RestCoordinatorAdmin(IConversationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** Cluster mode: other nodes' queue depths. Field-injected; null in tests. */
    @Inject
    IClusterRpc clusterRpc;

    /** Submits replays. Field-injected; null in tests. */
    @Inject
    IConversationService conversationService;

    @Override
    public CoordinatorStatus getStatus(String scope) {
        CoordinatorStatus status = coordinator.getStatus();
        if (!"cluster".equals(scope) || status.cluster() == null || clusterRpc == null || !clusterRpc.isClustered()) {
            return status;
        }
        Map<String, Object> cluster = new LinkedHashMap<>(status.cluster());
        Map<String, Object> perNode = new LinkedHashMap<>();
        perNode.put(status.nodeId(), status.queueDepths());
        clusterRpc.callAll(IClusterRpc.COORDINATOR_STATUS, Map.of()).forEach((node, reply) -> perNode.put(node, reply.get("queueDepths")));
        cluster.put("queueDepths", perNode);
        return new CoordinatorStatus(status.coordinatorType(), status.connected(), status.connectionStatus(), status.activeConversations(),
                status.totalProcessed(), status.totalDeadLettered(), status.queueDepths(), status.nodeId(), cluster);
    }

    @Override
    public List<DeadLetterEntry> getDeadLetters(int limit, String after) {
        List<DeadLetterEntry> all = coordinator.getDeadLetters();
        int max = limit <= 0 ? 100 : Math.min(limit, 1000);
        int start = 0;
        if (after != null) {
            for (int i = 0; i < all.size(); i++) {
                if (after.equals(all.get(i).id())) {
                    start = i + 1;
                    break;
                }
            }
        }
        return all.subList(Math.min(start, all.size()), Math.min(all.size(), start + max));
    }

    /**
     * A replay is a NEW turn: the failed task is gone, and re-running it would
     * repeat any side effect it already performed. The entry's captured input is
     * submitted through the conversation service as the calling admin, with the
     * context {@code replayOf=<entry id>}; the entry is removed only once the turn
     * was accepted. Used to delete the entry and answer "replayed" without
     * replaying anything.
     */
    @Override
    public void replayDeadLetter(String entryId) {
        DeadLetterEntry entry = coordinator.getDeadLetter(entryId)
                .orElseThrow(() -> new NotFoundException("Dead-letter entry not found: " + sanitize(entryId)));
        if (!entry.isReplayable() || conversationService == null) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT).type(MediaType.TEXT_PLAIN)
                    .entity("Dead-letter entry " + sanitize(entryId) + " cannot be replayed: its input was not captured "
                            + "(eddi.coordinator.dead-letter.capture-input=false, or not a conversation turn). Discard it instead.")
                    .build());
        }
        Map<String, Context> context = new HashMap<>();
        context.put("replayOf", new Context(Context.ContextType.string, entryId));
        InputData input = new InputData(String.valueOf(entry.turn().get("input")), context);
        try {
            conversationService.say(entry.conversationId(), false, true, List.of(), input, false, snapshot -> {
            });
        } catch (Exception e) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT).type(MediaType.TEXT_PLAIN)
                    .entity("Replay of " + sanitize(entryId) + " was not accepted (" + e.getClass().getSimpleName() + ": "
                            + sanitize(String.valueOf(e.getMessage())) + "); the entry is kept.")
                    .build());
        }
        coordinator.replayDeadLetter(entryId);
        log.infof("Dead-letter %s of conversation %s replayed as a new turn via REST", sanitize(entryId),
                sanitize(entry.conversationId()));
    }

    @Override
    public void discardDeadLetter(String entryId) {
        boolean discarded = coordinator.discardDeadLetter(entryId);
        if (!discarded) {
            throw new NotFoundException("Dead-letter entry not found: " + sanitize(entryId));
        }
        log.infof("Dead-letter %s discarded via REST", sanitize(entryId));
    }

    @Override
    public int purgeDeadLetters() {
        int count = coordinator.purgeDeadLetters();
        log.infof("Purged %d dead-letter entries via REST", count);
        return count;
    }

    @Override
    public void streamEvents(SseEventSink eventSink, Sse sse) {
        sseClients.add(eventSink);
        ensurePollerStarted(sse);

        // Send initial status immediately
        try {
            CoordinatorStatus status = coordinator.getStatus();
            OutboundSseEvent event = sse.newEventBuilder().name("status").data(status).build();
            eventSink.send(event);
        } catch (Exception e) {
            log.warnf(e, "Failed to send initial status to SSE client");
        }
    }

    /**
     * Start the SSE poller that broadcasts coordinator status to all connected
     * clients. Polls every 2 seconds and sends status snapshots + dead-letter count
     * changes.
     */
    private synchronized void ensurePollerStarted(Sse sse) {
        if (pollerStarted)
            return;
        pollerStarted = true;

        scheduler.scheduleAtFixedRate(() -> {
            // Remove closed clients
            sseClients.removeIf(SseEventSink::isClosed);

            if (sseClients.isEmpty())
                return;

            try {
                CoordinatorStatus status = coordinator.getStatus();
                OutboundSseEvent event = sse.newEventBuilder().name("status").data(status).build();

                for (SseEventSink client : sseClients) {
                    if (!client.isClosed()) {
                        client.send(event).exceptionally(t -> {
                            sseClients.remove(client);
                            return null;
                        });
                    }
                }
            } catch (Exception e) {
                log.debugf(e, "Error broadcasting SSE status");
            }
        }, 2, 2, TimeUnit.SECONDS);
    }
}
