package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IRestLogAdmin;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.engine.runtime.BoundedLogStore;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.runtime.InstanceIdProducer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * REST implementation for log administration — provides real-time SSE streaming
 * from the in-memory ring buffer and historical queries from the database.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestLogAdmin implements IRestLogAdmin {

    private static final Logger log = Logger.getLogger(RestLogAdmin.class);

    private final BoundedLogStore boundedLogStore;
    private final IDatabaseLogs databaseLogs;
    private final InstanceIdProducer instanceIdProducer;

    @Inject
    public RestLogAdmin(BoundedLogStore boundedLogStore, IDatabaseLogs databaseLogs, InstanceIdProducer instanceIdProducer) {
        this.boundedLogStore = boundedLogStore;
        this.databaseLogs = databaseLogs;
        this.instanceIdProducer = instanceIdProducer;
    }

    @Override
    public List<LogEntry> getRecentLogs(String agentId, String conversationId, String level, int limit) {
        return boundedLogStore.getEntries(agentId, conversationId, level, limit);
    }

    @Override
    public List<LogEntry> getHistoryLogs(Deployment.Environment environment, String agentId, Integer agentVersion, String conversationId,
            String userId, String instanceId, Integer skip, Integer limit) {
        return databaseLogs.getLogs(environment, agentId, agentVersion, conversationId, userId, instanceId, skip, limit);
    }

    @Override
    public void streamLogs(String agentId, String conversationId, String level, SseEventSink eventSink, Sse sse) {

        // Send initial batch from ring buffer
        List<LogEntry> initial = boundedLogStore.getEntries(agentId, conversationId, level, 50);
        for (int i = initial.size() - 1; i >= 0; i--) {
            sendEvent(eventSink, sse, initial.get(i));
        }

        // Register listener for live push
        String listenerId = boundedLogStore.addListener(entry -> {
            if (eventSink.isClosed())
                return;

            // Apply filters
            if (agentId != null && !agentId.equals(entry.agentId()))
                return;
            if (conversationId != null && !conversationId.equals(entry.conversationId()))
                return;
            if (level != null && !boundedLogStore.meetsMinimumLevel(entry.level(), level))
                return;

            sendEvent(eventSink, sse, entry);
        });

        // Clean up when client disconnects or after max lifetime
        Thread.ofVirtual().name("sse-log-cleanup-" + listenerId).start(() -> {
            long maxLifetimeMs = TimeUnit.HOURS.toMillis(24);
            long start = System.currentTimeMillis();
            try {
                while (!eventSink.isClosed() && (System.currentTimeMillis() - start) < maxLifetimeMs) {
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                boundedLogStore.removeListener(listenerId);
                if (!eventSink.isClosed()) {
                    eventSink.close();
                }
                log.debugv("SSE log listener {0} removed (client disconnected or max lifetime reached)", listenerId);
            }
        });

        log.debugv("SSE log stream started (listenerId={0}, agentId={1}, level={2})", listenerId, agentId, level);
    }

    @Override
    public InstanceInfo getInstanceId() {
        return new InstanceInfo(instanceIdProducer.getInstanceId());
    }

    private void sendEvent(SseEventSink eventSink, Sse sse, LogEntry entry) {
        try {
            OutboundSseEvent event = sse.newEventBuilder().name("log").data(entry).build();
            eventSink.send(event).exceptionally(t -> {
                log.debugv("Failed to send SSE log event: {0}", t.getMessage());
                return null;
            });
        } catch (Exception e) {
            log.debugv("Error sending SSE log event: {0}", e.getMessage());
        }
    }
}
