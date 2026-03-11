package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.nats.client.*;
import io.nats.client.api.*;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
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
 * <p><b>Dead-letter handling</b>: When a task fails more than {@code maxRetries} times,
 * the message is published to a dead-letter stream ({@code eddi.deadletter.<conversationId>})
 * with 30-day retention for operator inspection and replay.</p>
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
    private static final String DEAD_LETTER_PREFIX = "eddi.deadletter.";

    private final IRuntime runtime;
    private final Instance<NatsMetrics> metricsInstance;
    private final String natsUrl;
    private final String streamName;
    private final String deadLetterStreamName;
    private final int maxRetries;
    private final long ackWaitSeconds;

    private Connection natsConnection;
    private JetStream jetStream;

    /**
     * Per-conversation queue for local callable execution, backed by NATS ordering.
     * Key: conversationId, Value: queue of callables waiting execution.
     */
    private final Map<String, BlockingQueue<RetryableCallable>> conversationQueues = new ConcurrentHashMap<>();

    @Inject
    public NatsConversationCoordinator(
            IRuntime runtime,
            Instance<NatsMetrics> metricsInstance,
            @ConfigProperty(name = "eddi.nats.url", defaultValue = "nats://localhost:4222") String natsUrl,
            @ConfigProperty(name = "eddi.nats.stream-name", defaultValue = "EDDI_CONVERSATIONS") String streamName,
            @ConfigProperty(name = "eddi.nats.dead-letter-stream-name", defaultValue = "EDDI_DEAD_LETTERS") String deadLetterStreamName,
            @ConfigProperty(name = "eddi.nats.max-retries", defaultValue = "3") int maxRetries,
            @ConfigProperty(name = "eddi.nats.ack-wait-seconds", defaultValue = "60") long ackWaitSeconds) {
        this.runtime = runtime;
        this.metricsInstance = metricsInstance;
        this.natsUrl = natsUrl;
        this.streamName = streamName;
        this.deadLetterStreamName = deadLetterStreamName;
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

            // Create or update the main conversation stream
            StreamConfiguration streamConfig = StreamConfiguration.builder()
                    .name(streamName)
                    .subjects(SUBJECT_PREFIX + "*")
                    .retentionPolicy(RetentionPolicy.WorkQueue)
                    .maxAge(Duration.ofHours(24))
                    .storageType(StorageType.File)
                    .replicas(1)
                    .build();

            createOrUpdateStream(jsm, streamName, streamConfig);

            // Create or update the dead-letter stream (30-day retention)
            StreamConfiguration deadLetterConfig = StreamConfiguration.builder()
                    .name(deadLetterStreamName)
                    .subjects(DEAD_LETTER_PREFIX + "*")
                    .retentionPolicy(RetentionPolicy.Limits)
                    .maxAge(Duration.ofDays(30))
                    .storageType(StorageType.File)
                    .replicas(1)
                    .build();

            createOrUpdateStream(jsm, deadLetterStreamName, deadLetterConfig);

            jetStream = natsConnection.jetStream();
            log.info("NATS JetStream connection established successfully");

        } catch (IOException | InterruptedException | JetStreamApiException e) {
            log.errorf(e, "Failed to connect to NATS at %s", natsUrl);
            throw new RuntimeException("NATS connection failed", e);
        }
    }

    private void createOrUpdateStream(JetStreamManagement jsm, String name,
                                      StreamConfiguration config) throws IOException, JetStreamApiException {
        try {
            jsm.getStreamInfo(name);
            jsm.updateStream(config);
            log.infof("Updated existing NATS stream: %s", name);
        } catch (JetStreamApiException e) {
            jsm.addStream(config);
            log.infof("Created new NATS stream: %s", name);
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
        final BlockingQueue<RetryableCallable> queue = conversationQueues
                .computeIfAbsent(conversationId, k -> new LinkedTransferQueue<>());

        synchronized (queue) {
            boolean wasEmpty = queue.isEmpty();
            queue.offer(new RetryableCallable(callable));

            if (wasEmpty) {
                publishAndExecute(conversationId, queue, queue.element());
            }
        }
    }

    private void publishAndExecute(String conversationId, BlockingQueue<RetryableCallable> queue,
                                   RetryableCallable retryable) {
        String subject = SUBJECT_PREFIX + sanitizeSubject(conversationId);

        try {
            long startNanos = System.nanoTime();
            PublishAck ack = jetStream.publish(subject, conversationId.getBytes());
            long durationNanos = System.nanoTime() - startNanos;

            log.debugf("Published to NATS subject %s (seq: %d)", subject, ack.getSeqno());

            // Record publish metrics
            getMetrics().ifPresent(m -> {
                m.getPublishCount().increment();
                m.getPublishDuration().record(Duration.ofNanos(durationNanos));
            });
        } catch (IOException | JetStreamApiException e) {
            log.warnf(e, "Failed to publish to NATS for conversation %s, executing locally", conversationId);
        }

        // Execute the callable via the runtime thread pool
        long consumeStart = System.nanoTime();
        runtime.submitCallable(retryable.callable(), new IRuntime.IFinishedExecution<>() {
            @Override
            public void onComplete(Void result) {
                recordConsumeMetrics(consumeStart);
                submitNext(conversationId, queue);
            }

            @Override
            public void onFailure(Throwable t) {
                recordConsumeMetrics(consumeStart);
                int attempt = retryable.incrementAndGetAttempt();

                if (attempt < maxRetries) {
                    log.warnf(t, "Conversation task failed (conversationId=%s, attempt=%d/%d), retrying...",
                            conversationId, attempt, maxRetries);
                    // Re-execute the same callable (retry)
                    publishAndExecute(conversationId, queue, retryable);
                } else {
                    log.errorf(t, "Conversation task exhausted retries (conversationId=%s, attempts=%d), " +
                            "routing to dead-letter", conversationId, attempt);
                    routeToDeadLetter(conversationId, t);
                    submitNext(conversationId, queue);
                }
            }
        }, null);
    }

    private void recordConsumeMetrics(long startNanos) {
        long durationNanos = System.nanoTime() - startNanos;
        getMetrics().ifPresent(m -> {
            m.getConsumeCount().increment();
            m.getConsumeDuration().record(Duration.ofNanos(durationNanos));
        });
    }

    /**
     * Route a failed message to the dead-letter stream after all retries are exhausted.
     */
    private void routeToDeadLetter(String conversationId, Throwable failure) {
        String deadLetterSubject = DEAD_LETTER_PREFIX + sanitizeSubject(conversationId);

        try {
            String payload = String.format(
                    "{\"conversationId\":\"%s\",\"error\":\"%s\",\"timestamp\":%d}",
                    conversationId,
                    failure.getMessage() != null ? failure.getMessage().replace("\"", "\\\"") : "unknown",
                    System.currentTimeMillis());

            jetStream.publish(deadLetterSubject, payload.getBytes());
            log.infof("Published dead-letter for conversation %s to %s", conversationId, deadLetterSubject);

            getMetrics().ifPresent(m -> m.getDeadLetterCount().increment());
        } catch (IOException | JetStreamApiException e) {
            log.errorf(e, "Failed to publish dead-letter for conversation %s", conversationId);
        }
    }

    private void submitNext(String conversationId, BlockingQueue<RetryableCallable> queue) {
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
    String sanitizeSubject(String conversationId) {
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

    /**
     * @return the max retries configuration value (for testing)
     */
    int getMaxRetries() {
        return maxRetries;
    }

    private java.util.Optional<NatsMetrics> getMetrics() {
        if (metricsInstance != null && metricsInstance.isResolvable()) {
            return java.util.Optional.of(metricsInstance.get());
        }
        return java.util.Optional.empty();
    }

    /**
     * Wraps a Callable with a retry attempt counter.
     */
    static class RetryableCallable {
        private final Callable<Void> callable;
        private int attempt;

        RetryableCallable(Callable<Void> callable) {
            this.callable = callable;
            this.attempt = 0;
        }

        Callable<Void> callable() {
            return callable;
        }

        int incrementAndGetAttempt() {
            return ++attempt;
        }

        int getAttempt() {
            return attempt;
        }
    }
}
