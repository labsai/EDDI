package ai.labs.eddi.engine.internal;

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

    @Override
    public CoordinatorStatus getStatus() {
        return coordinator.getStatus();
    }

    @Override
    public List<DeadLetterEntry> getDeadLetters() {
        return coordinator.getDeadLetters();
    }

    @Override
    public void replayDeadLetter(String entryId) {
        boolean replayed = coordinator.replayDeadLetter(entryId);
        if (!replayed) {
            throw new NotFoundException("Dead-letter entry not found: " + sanitize(entryId));
        }
        log.infof("Dead-letter %s replayed via REST", sanitize(entryId));
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
