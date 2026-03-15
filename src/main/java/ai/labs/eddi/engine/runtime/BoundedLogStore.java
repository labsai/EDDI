package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.LogEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * In-memory ring buffer that captures JUL log records with MDC context.
 * Provides:
 * <ul>
 *   <li>Instant in-memory access for the SSE live-tail endpoint</li>
 *   <li>Listener-based push for connected SSE clients</li>
 *   <li>Async batched DB persistence via {@link IDatabaseLogs}</li>
 * </ul>
 *
 * <p>Replaces the direct JUL Handler role previously held by DatabaseLogs/PostgresDatabaseLogs.
 * Those classes now only provide the persistence API; this class is the single registered handler.</p>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class BoundedLogStore {

    private static final Logger log = Logger.getLogger(BoundedLogStore.class);

    private final int bufferSize;
    private final boolean dbEnabled;
    private final int dbFlushIntervalSeconds;
    private final String dbPersistMinLevel;

    private final InstanceIdProducer instanceIdProducer;
    private final IDatabaseLogs databaseLogs;

    // Ring buffer
    private final ArrayDeque<LogEntry> buffer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // SSE listeners
    private final Map<String, Consumer<LogEntry>> listeners = new ConcurrentHashMap<>();

    // Async DB writer
    private final ConcurrentLinkedQueue<LogEntry> dbQueue = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService dbWriter;

    // JUL handler instance
    private LogCaptureHandler julHandler;

    @Inject
    public BoundedLogStore(
            InstanceIdProducer instanceIdProducer,
            IDatabaseLogs databaseLogs,
            @ConfigProperty(name = "eddi.logs.buffer-size", defaultValue = "10000") int bufferSize,
            @ConfigProperty(name = "eddi.logs.db-enabled", defaultValue = "true") boolean dbEnabled,
            @ConfigProperty(name = "eddi.logs.db-flush-interval-seconds", defaultValue = "5") int dbFlushIntervalSeconds,
            @ConfigProperty(name = "eddi.logs.db-persist-min-level", defaultValue = "WARN") String dbPersistMinLevel) {

        this.instanceIdProducer = instanceIdProducer;
        this.databaseLogs = databaseLogs;
        this.bufferSize = bufferSize;
        this.dbEnabled = dbEnabled;
        this.dbFlushIntervalSeconds = dbFlushIntervalSeconds;
        this.dbPersistMinLevel = dbPersistMinLevel.toUpperCase();
        this.buffer = new ArrayDeque<>(bufferSize);
    }

    /**
     * Factory method for testing — creates a store without CDI.
     * Call {@link #init()} after construction to register the JUL handler.
     */
    static BoundedLogStore createForTesting(InstanceIdProducer instanceIdProducer, IDatabaseLogs databaseLogs,
                                             int bufferSize, boolean dbEnabled, int dbFlushIntervalSeconds,
                                             String dbPersistMinLevel) {
        return new BoundedLogStore(instanceIdProducer, databaseLogs, bufferSize,
                dbEnabled, dbFlushIntervalSeconds, dbPersistMinLevel);
    }

    @PostConstruct
    void init() {
        // Register as JUL handler on root logger
        julHandler = new LogCaptureHandler(this);
        java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
        rootLogger.addHandler(julHandler);

        // Start async DB writer if enabled
        if (dbEnabled) {
            dbWriter = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "log-db-writer");
                t.setDaemon(true);
                return t;
            });
            dbWriter.scheduleAtFixedRate(this::flushToDb,
                    dbFlushIntervalSeconds, dbFlushIntervalSeconds, TimeUnit.SECONDS);
            log.infov("BoundedLogStore: DB persistence enabled (flush every {0}s, min level: {1})",
                    dbFlushIntervalSeconds, dbPersistMinLevel);
        } else {
            log.info("BoundedLogStore: DB persistence disabled (ring buffer + SSE only)");
        }

        log.infov("BoundedLogStore initialized (buffer={0}, dbEnabled={1})", bufferSize, dbEnabled);
    }

    @PreDestroy
    void shutdown() {
        // Remove JUL handler
        if (julHandler != null) {
            java.util.logging.Logger.getLogger("").removeHandler(julHandler);
        }

        // Final flush
        if (dbEnabled && dbWriter != null) {
            flushToDb();
            dbWriter.shutdown();
            try {
                if (!dbWriter.awaitTermination(5, TimeUnit.SECONDS)) {
                    dbWriter.shutdownNow();
                }
            } catch (InterruptedException e) {
                dbWriter.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Publish a log entry to the ring buffer and notify listeners.
     * Called from the JUL handler on the logging thread.
     */
    public void publish(LogEntry entry) {
        // Add to ring buffer
        lock.writeLock().lock();
        try {
            if (buffer.size() >= bufferSize) {
                buffer.pollFirst(); // evict oldest
            }
            buffer.addLast(entry);
        } finally {
            lock.writeLock().unlock();
        }

        // Notify SSE listeners (non-blocking)
        for (Consumer<LogEntry> listener : listeners.values()) {
            try {
                listener.accept(entry);
            } catch (Exception e) {
                // Don't let a bad listener break logging
            }
        }

        // Enqueue for DB persistence
        if (dbEnabled && meetsMinLevel(entry.level())) {
            dbQueue.offer(entry);
        }
    }

    /**
     * Get entries from the ring buffer, optionally filtered.
     */
    public List<LogEntry> getEntries(String botId, String conversationId, String level, int limit) {
        lock.readLock().lock();
        try {
            List<LogEntry> result = new ArrayList<>();
            // Iterate newest-first (from tail)
            Iterator<LogEntry> it = buffer.descendingIterator();
            while (it.hasNext() && result.size() < limit) {
                LogEntry entry = it.next();
                if (matchesFilter(entry, botId, conversationId, level)) {
                    result.add(entry);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Register an SSE listener. Returns a listener ID for removal.
     */
    public String addListener(Consumer<LogEntry> listener) {
        String id = UUID.randomUUID().toString();
        listeners.put(id, listener);
        return id;
    }

    /**
     * Remove a previously registered listener.
     */
    public void removeListener(String listenerId) {
        listeners.remove(listenerId);
    }

    /**
     * Flush pending log entries to the database in a single batch.
     */
    void flushToDb() {
        if (dbQueue.isEmpty()) return;

        List<LogEntry> batch = new ArrayList<>();
        LogEntry entry;
        while ((entry = dbQueue.poll()) != null) {
            batch.add(entry);
        }

        if (!batch.isEmpty()) {
            try {
                databaseLogs.addLogsBatch(batch);
            } catch (Exception e) {
                log.errorv("Failed to flush {0} log entries to DB: {1}", batch.size(), e.getMessage());
            }
        }
    }

    // Visible for testing
    int getBufferSize() {
        lock.readLock().lock();
        try {
            return buffer.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    int getListenerCount() {
        return listeners.size();
    }

    int getDbQueueSize() {
        return dbQueue.size();
    }

    private boolean matchesFilter(LogEntry entry, String botId, String conversationId, String level) {
        if (botId != null && !botId.equals(entry.botId())) return false;
        if (conversationId != null && !conversationId.equals(entry.conversationId())) return false;
        if (level != null && !level.equalsIgnoreCase(entry.level())) return false;
        return true;
    }

    private boolean meetsMinLevel(String level) {
        if (level == null) return false;
        return levelOrdinal(level) >= levelOrdinal(dbPersistMinLevel);
    }

    private static int levelOrdinal(String level) {
        return switch (level.toUpperCase()) {
            case "SEVERE", "FATAL", "ERROR" -> 5;
            case "WARNING", "WARN" -> 4;
            case "INFO" -> 3;
            case "CONFIG" -> 2;
            case "FINE", "DEBUG" -> 1;
            case "FINER", "FINEST", "TRACE" -> 0;
            default -> 3; // default to INFO
        };
    }

    /**
     * Inner JUL Handler that captures log records and delegates to BoundedLogStore.
     */
    private static class LogCaptureHandler extends Handler {

        private final BoundedLogStore store;

        LogCaptureHandler(BoundedLogStore store) {
            this.store = store;
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getMessage() == null) return;

            // Don't capture our own log messages to avoid infinite recursion
            String loggerName = record.getLoggerName();
            if (loggerName != null && loggerName.startsWith("ai.labs.eddi.engine.runtime.BoundedLogStore")) {
                return;
            }

            // Read MDC context (set by ContextLogger in ConversationService)
            String environment = (String) MDC.get("environment");
            String botId = (String) MDC.get("botId");
            String conversationId = (String) MDC.get("conversationId");
            String userId = (String) MDC.get("userId");
            Integer botVersion = null;
            try {
                String bv = (String) MDC.get("botVersion");
                if (bv != null) botVersion = Integer.parseInt(bv);
            } catch (NumberFormatException ignored) {
            }

            LogEntry entry = new LogEntry(
                    record.getMillis(),
                    record.getLevel().getName(),
                    loggerName,
                    record.getMessage(),
                    environment,
                    botId,
                    botVersion,
                    conversationId,
                    userId,
                    store.instanceIdProducer.getInstanceId()
            );

            store.publish(entry);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }
    }
}
