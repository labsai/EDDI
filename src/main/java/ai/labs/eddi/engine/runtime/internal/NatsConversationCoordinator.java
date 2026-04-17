package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.nats.client.*;
import io.nats.client.api.*;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NATS JetStream-backed implementation of {@link IConversationCoordinator}.
 *
 * <p>
 * Uses NATS JetStream for durable, ordered message processing per conversation.
 * Activated when {@code eddi.messaging.type=nats} is set in
 * application.properties.
 * </p>
 *
 * <p>
 * <b>Design</b>: NATS is used as a distributed ordering primitive. Callables
 * execute in-process on the publishing JVM. NATS guarantees per-subject
 * ordering via its built-in queue semantics. Each conversation gets its own
 * NATS subject ({@code eddi.conversation.<conversationId>}) ensuring sequential
 * processing.
 * </p>
 *
 * <p>
 * <b>Dead-letter handling</b>: When a task fails more than {@code maxRetries}
 * times, the message is published to a dead-letter stream
 * ({@code eddi.deadletter.<conversationId>}) with 30-day retention for operator
 * inspection and replay.
 * </p>
 *
 * <p>
 * For horizontal scaling, a future enhancement will serialize InputData instead
 * of Callable, allowing cross-instance message consumption.
 * </p>
 *
 * <h3>Hardening (v6.0.2)</h3>
 * <ul>
 * <li><b>Eager cleanup</b>: Queues are removed from the map as soon as they
 * become empty, preventing memory leaks from abandoned conversations.</li>
 * <li><b>Max-size limit</b>: Configurable cap on active conversations
 * ({@code eddi.coordinator.max-active-conversations}). Follow-up messages to
 * currently-queued conversations are always accepted; conversations that have
 * fully drained are treated as new.</li>
 * <li><b>Micrometer metrics</b>: {@code eddi.coordinator.active_conversations},
 * {@code eddi.coordinator.queue_depth},
 * {@code eddi.coordinator.total_processed}.</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 * @see ai.labs.eddi.engine.runtime.IEventBus
 */
@ApplicationScoped
@IfBuildProfile("nats")
public class NatsConversationCoordinator implements IConversationCoordinator {

    private static final Logger log = Logger.getLogger(NatsConversationCoordinator.class);
    private static final String SUBJECT_PREFIX = "eddi.conversation.";
    private static final String DEAD_LETTER_PREFIX = "eddi.deadletter.";

    private final IRuntime runtime;
    private final Instance<NatsMetrics> metricsInstance;
    private final MeterRegistry meterRegistry;
    private final String natsUrl;
    private final String streamName;
    private final String deadLetterStreamName;
    private final int maxRetries;
    private final int maxActiveConversations;

    private Connection natsConnection;
    private JetStream jetStream;

    /**
     * Per-conversation queue for local callable execution, backed by NATS ordering.
     * Key: conversationId, Value: queue of callables waiting execution.
     */
    private final Map<String, BlockingQueue<RetryableCallable>> conversationQueues = new ConcurrentHashMap<>();
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalDeadLettered = new AtomicLong(0);

    @Inject
    public NatsConversationCoordinator(IRuntime runtime, Instance<NatsMetrics> metricsInstance,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.nats.url", defaultValue = "nats://localhost:4222") String natsUrl,
            @ConfigProperty(name = "eddi.nats.stream-name", defaultValue = "EDDI_CONVERSATIONS") String streamName,
            @ConfigProperty(name = "eddi.nats.dead-letter-stream-name", defaultValue = "EDDI_DEAD_LETTERS") String deadLetterStreamName,
            @ConfigProperty(name = "eddi.nats.max-retries", defaultValue = "3") int maxRetries,
            @ConfigProperty(name = "eddi.coordinator.max-active-conversations", defaultValue = "10000") int maxActiveConversations) {
        this.runtime = runtime;
        this.metricsInstance = metricsInstance;
        this.meterRegistry = meterRegistry;
        this.natsUrl = natsUrl;
        this.streamName = streamName;
        this.deadLetterStreamName = deadLetterStreamName;
        this.maxRetries = maxRetries;
        this.maxActiveConversations = maxActiveConversations;
    }

    @PostConstruct
    void init() {
        start();

        // Register coordinator-level Micrometer metrics after successful start
        meterRegistry.gauge("eddi.coordinator.active_conversations", conversationQueues, Map::size);
        meterRegistry.gauge("eddi.coordinator.queue_depth", conversationQueues, this::computeTotalQueueDepth);
        FunctionCounter.builder("eddi.coordinator.total_processed", totalProcessed, AtomicLong::doubleValue)
                .description("Total conversation tasks processed")
                .register(meterRegistry);
    }

    private double computeTotalQueueDepth(Map<String, BlockingQueue<RetryableCallable>> queues) {
        return queues.values().stream().mapToInt(BlockingQueue::size).sum();
    }

    @Override
    public void start() {
        try {
            log.infof("Connecting to NATS at %s (stream: %s)", natsUrl, streamName);

            Options options = new Options.Builder().server(natsUrl).connectionTimeout(Duration.ofSeconds(10)).reconnectWait(Duration.ofSeconds(2))
                    .maxReconnects(-1) // unlimited reconnects
                    .connectionListener((conn, type) -> log.infof("NATS connection event: %s", type)).errorListener(new ErrorListener() {
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
                    }).build();

            natsConnection = Nats.connect(options);
            JetStreamManagement jsm = natsConnection.jetStreamManagement();

            // Create or update the main conversation stream
            StreamConfiguration streamConfig = StreamConfiguration.builder().name(streamName).subjects(SUBJECT_PREFIX + "*")
                    .retentionPolicy(RetentionPolicy.WorkQueue).maxAge(Duration.ofHours(24)).storageType(StorageType.File).replicas(1).build();

            createOrUpdateStream(jsm, streamName, streamConfig);

            // Create or update the dead-letter stream (30-day retention)
            StreamConfiguration deadLetterConfig = StreamConfiguration.builder().name(deadLetterStreamName).subjects(DEAD_LETTER_PREFIX + "*")
                    .retentionPolicy(RetentionPolicy.Limits).maxAge(Duration.ofDays(30)).storageType(StorageType.File).replicas(1).build();

            createOrUpdateStream(jsm, deadLetterStreamName, deadLetterConfig);

            jetStream = natsConnection.jetStream();
            log.info("NATS JetStream connection established successfully");

        } catch (IOException | InterruptedException | JetStreamApiException e) {
            log.errorf(e, "Failed to connect to NATS at %s", natsUrl);
            throw new RuntimeException("NATS connection failed", e);
        }
    }

    private void createOrUpdateStream(JetStreamManagement jsm, String name, StreamConfiguration config) throws IOException, JetStreamApiException {
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
     * <p>
     * Current behavior (single-instance): Uses NATS for ordering acknowledgment
     * while executing the callable locally. The NATS subject per conversation
     * ensures only one message is active per conversation at a time.
     * </p>
     *
     * @param conversationId
     *            the unique conversation identifier
     * @param callable
     *            the task to execute
     */
    @Override
    public void submitInOrder(String conversationId, Callable<Void> callable) {
        // Max-size check: only reject truly new conversations, not follow-up messages.
        // Note: this check is intentionally non-atomic (soft limit).
        if (!conversationQueues.containsKey(conversationId) && conversationQueues.size() >= maxActiveConversations) {
            log.warnf("Coordinator capacity exceeded (%d active conversations). Rejecting new conversationId=%s", maxActiveConversations,
                    conversationId);
            throw new java.util.concurrent.RejectedExecutionException(
                    "Coordinator capacity exceeded: " + maxActiveConversations + " active conversations. Try again later.");
        }

        // CAS loop: after acquiring the lock on the queue, verify it's still the
        // map's current value. If submitNext() removed it (eager cleanup) between
        // our computeIfAbsent and our synchronized(queue), the queue is orphaned
        // and we must retry with a fresh computeIfAbsent.
        //
        // Happens-before correctness: ConcurrentHashMap.get() is ordered after
        // the monitor release in submitNext's synchronized(queue) block, so our
        // identity check sees the removal. In practice, retries are 0–1.
        for (int attempt = 0;; attempt++) {
            final BlockingQueue<RetryableCallable> queue = conversationQueues.computeIfAbsent(conversationId, k -> new LinkedTransferQueue<>());

            synchronized (queue) {
                if (conversationQueues.get(conversationId) != queue) {
                    if (attempt >= 3) {
                        log.debugf("CAS loop retried %d times for conversationId=%s (expected 0-1)", attempt, conversationId);
                    }
                    continue; // retry with fresh computeIfAbsent
                }

                boolean wasEmpty = queue.isEmpty();
                boolean enqueued = queue.offer(new RetryableCallable(callable));
                if (!enqueued) {
                    log.warnf("Failed to enqueue task for conversationId=%s", conversationId);
                    throw new java.util.concurrent.RejectedExecutionException(
                            "Failed to enqueue task for conversationId=" + conversationId);
                }

                if (wasEmpty) {
                    publishAndExecute(conversationId, queue, queue.element());
                }
                return; // success
            }
        }
    }

    private void publishAndExecute(String conversationId, BlockingQueue<RetryableCallable> queue, RetryableCallable retryable) {
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
                totalProcessed.incrementAndGet();
                submitNext(conversationId, queue);
            }

            @Override
            public void onFailure(Throwable t) {
                recordConsumeMetrics(consumeStart);
                int attempt = retryable.incrementAndGetAttempt();

                if (attempt < maxRetries) {
                    log.warnf(t, "Conversation task failed (conversationId=%s, attempt=%d/%d), retrying...", conversationId, attempt, maxRetries);
                    // Re-execute the same callable (retry)
                    publishAndExecute(conversationId, queue, retryable);
                } else {
                    log.errorf(t, "Conversation task exhausted retries (conversationId=%s, attempts=%d), " + "routing to dead-letter", conversationId,
                            attempt);
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
     * Route a failed message to the dead-letter stream after all retries are
     * exhausted.
     */
    private void routeToDeadLetter(String conversationId, Throwable failure) {
        String deadLetterSubject = DEAD_LETTER_PREFIX + sanitizeSubject(conversationId);

        try {
            String payload = String.format("{\"conversationId\":\"%s\",\"error\":\"%s\",\"timestamp\":%d}", conversationId,
                    failure.getMessage() != null ? failure.getMessage().replace("\"", "\\\"") : "unknown", System.currentTimeMillis());

            jetStream.publish(deadLetterSubject, payload.getBytes());
            log.infof("Published dead-letter for conversation %s to %s", conversationId, deadLetterSubject);

            totalDeadLettered.incrementAndGet();
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
                } else {
                    // Eager cleanup: remove empty queue to prevent memory leaks.
                    conversationQueues.remove(conversationId, queue);
                }
            }
        }
    }

    /**
     * Sanitize conversation ID for use as NATS subject token. NATS subjects cannot
     * contain spaces or dots.
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

    // ==================== Status Methods ====================

    @Override
    public String getCoordinatorType() {
        return "nats";
    }

    @Override
    public boolean isConnected() {
        return natsConnection != null && natsConnection.getStatus() == Connection.Status.CONNECTED;
    }

    @Override
    public String getConnectionStatus() {
        if (natsConnection == null) {
            return "NOT_INITIALIZED";
        }
        return natsConnection.getStatus().name();
    }

    @Override
    public Map<String, Integer> getQueueDepths() {
        Map<String, Integer> depths = new LinkedHashMap<>();
        conversationQueues.forEach((id, q) -> {
            int size = q.size();
            if (size > 0) {
                depths.put(id, size);
            }
        });
        return depths;
    }

    @Override
    public long getTotalProcessed() {
        return totalProcessed.get();
    }

    @Override
    public long getTotalDeadLettered() {
        return totalDeadLettered.get();
    }

    // ==================== Dead-Letter Methods ====================

    @Override
    public List<DeadLetterEntry> getDeadLetters() {
        List<DeadLetterEntry> entries = new ArrayList<>();
        if (natsConnection == null || jetStream == null)
            return entries;

        try {
            JetStreamSubscription sub = jetStream.subscribe(DEAD_LETTER_PREFIX + "*");
            Message msg;
            while ((msg = sub.nextMessage(Duration.ofMillis(500))) != null) {
                String payload = new String(msg.getData(), StandardCharsets.UTF_8);
                String subject = msg.getSubject();
                String convId = subject.replace(DEAD_LETTER_PREFIX, "");

                entries.add(new DeadLetterEntry(String.valueOf(msg.metaData() != null ? msg.metaData().streamSequence() : entries.size()), convId,
                        extractField(payload, "error"), extractTimestamp(payload), payload));
            }
            sub.unsubscribe();
        } catch (Exception e) {
            log.warnf(e, "Failed to list dead-letter entries");
        }
        return entries;
    }

    @Override
    public boolean discardDeadLetter(String entryId) {
        // For NATS, we can't selectively delete individual messages from a stream.
        // Instead, we log it and let the operator know.
        log.infof("Dead-letter %s acknowledged (NATS stream messages expire via retention policy)", entryId);
        return true;
    }

    @Override
    public int purgeDeadLetters() {
        if (natsConnection == null)
            return 0;
        try {
            JetStreamManagement jsm = natsConnection.jetStreamManagement();
            PurgeResponse response = jsm.purgeStream(deadLetterStreamName);
            int purged = (int) response.getPurged();
            log.infof("Purged %d dead-letter messages from stream %s", purged, deadLetterStreamName);
            return purged;
        } catch (IOException | JetStreamApiException e) {
            log.errorf(e, "Failed to purge dead-letter stream %s", deadLetterStreamName);
            return 0;
        }
    }

    private String extractField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0)
            return "unknown";
        start += key.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "unknown";
    }

    private long extractTimestamp(String json) {
        String key = "\"timestamp\":";
        int start = json.indexOf(key);
        if (start < 0)
            return 0;
        start += key.length();
        int end = json.indexOf("}", start);
        try {
            return Long.parseLong(json.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * @return the max retries configuration value (for testing)
     */
    int getMaxRetries() {
        return maxRetries;
    }

    private Optional<NatsMetrics> getMetrics() {
        if (metricsInstance != null && metricsInstance.isResolvable()) {
            return Optional.of(metricsInstance.get());
        }
        return Optional.empty();
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
