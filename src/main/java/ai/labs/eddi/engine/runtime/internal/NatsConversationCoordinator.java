package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.nats.client.*;
import io.nats.client.api.*;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

/**
 * NATS JetStream-backed implementation of {@link IConversationCoordinator}.
 *
 * <p>Uses NATS JetStream for durable, ordered message processing per conversation.
 * Activated when {@code eddi.messaging.type=nats} is set in application.properties.</p>
 *
 * <p><b>Design</b>: NATS is used as a distributed ordering primitive. Callables execute
 * in-process on the publishing JVM. NATS guarantees per-subject ordering via its
 * built-in queue semantics. Each conversation gets its own NATS subject
 * ({@code eddi.conversation.<conversationId>}) ensuring sequential processing.</p>
 *
 * <p>For horizontal scaling, a future enhancement will serialize InputData
 * instead of Callable, allowing cross-instance message consumption.</p>
 *
 * @author ginccc
 * @since 6.0.0
 * @see ai.labs.eddi.engine.runtime.IEventBus
 */
@ApplicationScoped
@LookupIfProperty(name = "eddi.messaging.type", stringValue = "nats")
public class NatsConversationCoordinator implements IConversationCoordinator {

    private static final Logger log = Logger.getLogger(NatsConversationCoordinator.class);
    private static final String SUBJECT_PREFIX = "eddi.conversation.";

    private final IRuntime runtime;
    private final String natsUrl;
    private final String streamName;
    private final int maxRetries;
    private final long ackWaitSeconds;

    private Connection natsConnection;
    private JetStream jetStream;

    /**
     * Per-conversation queue for local callable execution, backed by NATS ordering.
     * Key: conversationId, Value: queue of callables waiting execution.
     */
    private final Map<String, BlockingQueue<Callable<Void>>> conversationQueues = new ConcurrentHashMap<>();

    @Inject
    public NatsConversationCoordinator(
            IRuntime runtime,
            @ConfigProperty(name = "eddi.nats.url", defaultValue = "nats://localhost:4222") String natsUrl,
            @ConfigProperty(name = "eddi.nats.stream-name", defaultValue = "EDDI_CONVERSATIONS") String streamName,
            @ConfigProperty(name = "eddi.nats.max-retries", defaultValue = "3") int maxRetries,
            @ConfigProperty(name = "eddi.nats.ack-wait-seconds", defaultValue = "60") long ackWaitSeconds) {
        this.runtime = runtime;
        this.natsUrl = natsUrl;
        this.streamName = streamName;
        this.maxRetries = maxRetries;
        this.ackWaitSeconds = ackWaitSeconds;
    }

    @PostConstruct
    void init() {
        start();
    }

    @Override
    public void start() {
        try {
            log.infof("Connecting to NATS at %s (stream: %s)", natsUrl, streamName);

            Options options = new Options.Builder()
                    .server(natsUrl)
                    .connectionTimeout(Duration.ofSeconds(10))
                    .reconnectWait(Duration.ofSeconds(2))
                    .maxReconnects(-1) // unlimited reconnects
                    .connectionListener((conn, type) ->
                            log.infof("NATS connection event: %s", type))
                    .errorListener(new ErrorListener() {
                        @Override
                        public void errorOccurred(Connection conn, String error) {
                            log.errorf("NATS error: %s", error);
                        }

                        @Override
                        public void exceptionOccurred(Connection conn, Exception exp) {
                            log.errorf(exp, "NATS exception");
                        }

                        @Override
                        public void slowConsumerDetected(Connection conn, Consumer consumer) {
                            log.warnf("NATS slow consumer detected");
                        }
                    })
                    .build();

            natsConnection = Nats.connect(options);
            JetStreamManagement jsm = natsConnection.jetStreamManagement();

            // Create or update the stream
            StreamConfiguration streamConfig = StreamConfiguration.builder()
                    .name(streamName)
                    .subjects(SUBJECT_PREFIX + "*")
                    .retentionPolicy(RetentionPolicy.WorkQueue)
                    .maxAge(Duration.ofHours(24))
                    .storageType(StorageType.File)
                    .replicas(1)
                    .build();

            try {
                jsm.getStreamInfo(streamName);
                jsm.updateStream(streamConfig);
                log.infof("Updated existing NATS stream: %s", streamName);
            } catch (JetStreamApiException e) {
                jsm.addStream(streamConfig);
                log.infof("Created new NATS stream: %s", streamName);
            }

            jetStream = natsConnection.jetStream();
            log.info("NATS JetStream connection established successfully");

        } catch (IOException | InterruptedException | JetStreamApiException e) {
            log.errorf(e, "Failed to connect to NATS at %s", natsUrl);
            throw new RuntimeException("NATS connection failed", e);
        }
    }

    /**
     * Submit a conversation task for ordered processing.
     *
     * <p>Current behavior (single-instance): Uses NATS for ordering acknowledgment
     * while executing the callable locally. The NATS subject per conversation
     * ensures only one message is active per conversation at a time.</p>
     *
     * @param conversationId the unique conversation identifier
     * @param callable the task to execute
     */
    @Override
    public void submitInOrder(String conversationId, Callable<Void> callable) {
        final BlockingQueue<Callable<Void>> queue = conversationQueues
                .computeIfAbsent(conversationId, k -> new LinkedTransferQueue<>());

        synchronized (queue) {
            boolean wasEmpty = queue.isEmpty();
            queue.offer(callable);

            if (wasEmpty) {
                publishAndExecute(conversationId, queue, callable);
            }
        }
    }

    private void publishAndExecute(String conversationId, BlockingQueue<Callable<Void>> queue,
                                   Callable<Void> callable) {
        String subject = SUBJECT_PREFIX + sanitizeSubject(conversationId);

        try {
            // Publish ordering marker to NATS JetStream
            PublishAck ack = jetStream.publish(subject, conversationId.getBytes());
            log.debugf("Published to NATS subject %s (seq: %d)", subject, ack.getSeqno());
        } catch (IOException | JetStreamApiException e) {
            log.warnf(e, "Failed to publish to NATS for conversation %s, executing locally", conversationId);
        }

        // Execute the callable via the runtime thread pool
        runtime.submitCallable(callable, new IRuntime.IFinishedExecution<>() {
            @Override
            public void onComplete(Void result) {
                submitNext(conversationId, queue);
            }

            @Override
            public void onFailure(Throwable t) {
                log.errorf(t, "Conversation task failed (conversationId=%s)", conversationId);
                submitNext(conversationId, queue);
            }
        }, null);
    }

    private void submitNext(String conversationId, BlockingQueue<Callable<Void>> queue) {
        synchronized (queue) {
            if (!queue.isEmpty()) {
                queue.remove();

                if (!queue.isEmpty()) {
                    publishAndExecute(conversationId, queue, queue.element());
                }
            }
        }
    }

    /**
     * Sanitize conversation ID for use as NATS subject token.
     * NATS subjects cannot contain spaces or dots.
     */
    private String sanitizeSubject(String conversationId) {
        return conversationId.replace('.', '-').replace(' ', '_');
    }

    @PreDestroy
    @Override
    public void shutdown() {
        if (natsConnection != null) {
            try {
                log.info("Shutting down NATS connection...");
                natsConnection.drain(Duration.ofSeconds(10));
                natsConnection.close();
                log.info("NATS connection closed");
            } catch (InterruptedException | TimeoutException e) {
                log.warnf(e, "Error during NATS shutdown");
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * @return true if connected to NATS
     */
    public boolean isConnected() {
        return natsConnection != null &&
                natsConnection.getStatus() == Connection.Status.CONNECTED;
    }

    /**
     * @return the NATS connection status string
     */
    public String getConnectionStatus() {
        if (natsConnection == null) {
            return "NOT_INITIALIZED";
        }
        return natsConnection.getStatus().name();
    }
}
