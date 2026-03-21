package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * In-memory ring buffer that captures log records with MDC context.
 * Provides:
 * <ul>
 *   <li>Instant in-memory access for the SSE live-tail endpoint</li>
 *   <li>Listener-based push for connected SSE clients</li>
 *   <li>Async batched DB persistence via {@link IDatabaseLogs}</li>
 * </ul>
 *
 * <p>Log records are captured via {@link LogCaptureFilter}, a Quarkus
 * {@code @LoggingFilter} that intercepts every log record in the
 * JBoss LogManager pipeline and pushes it to this store.</p>
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
     * Call {@link #init()} after construction to start the DB writer.
     */
    static BoundedLogStore createForTesting(InstanceIdProducer instanceIdProducer, IDatabaseLogs databaseLogs,
                                             int bufferSize, boolean dbEnabled, int dbFlushIntervalSeconds,
                                             String dbPersistMinLevel) {
        return new BoundedLogStore(instanceIdProducer, databaseLogs, bufferSize,
                dbEnabled, dbFlushIntervalSeconds, dbPersistMinLevel);
    }

    @PostConstruct
    void init() {
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
        // Final flush
        if (dbEnabled && dbWriter != null) {
            flushToDb();
            dbWriter.shutdown();
            try {
                if (!dbWriter.awaitTermination(5, TimeUnit.SECONDS)) {
                    dbWriter.shutdownNow();
                }
            } catch (InterruptedException _) {
                dbWriter.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Called by {@link LogCaptureFilter} for every log record passing through
     * the Quarkus logging pipeline.
     *
     * @param record the JUL LogRecord (actually a JBoss ExtLogRecord at runtime)
     */
    public void capture(java.util.logging.LogRecord record) {
        if (record == null) return;

        // Format the message using a Formatter (avoids deprecated getFormattedMessage())
        String message = formatRecord(record);
        if (message == null || message.isEmpty()) return;

        // Redact potential secrets from log messages (defense-in-depth)
        message = SecretRedactionFilter.redact(message);

        // Don't capture our own log messages to avoid infinite recursion
        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.startsWith("ai.labs.eddi.engine.runtime.BoundedLogStore")) {
            return;
        }

        // Read MDC context from ExtLogRecord if available, otherwise from SLF4J MDC
        String environment = null;
        String botId = null;
        String conversationId = null;
        String userId = null;
        Integer botVersion = null;

        if (record instanceof org.jboss.logmanager.ExtLogRecord extRecord) {
            environment = extRecord.getMdc("environment");
            botId = extRecord.getMdc("botId");
            conversationId = extRecord.getMdc("conversationId");
            userId = extRecord.getMdc("userId");
            String bv = extRecord.getMdc("botVersion");
            if (bv != null) {
                try {
                    botVersion = Integer.parseInt(bv);
                } catch (NumberFormatException _) {
                }
            }
        } else {
            // Fallback: read from SLF4J MDC
            environment = org.slf4j.MDC.get("environment");
            botId = org.slf4j.MDC.get("botId");
            conversationId = org.slf4j.MDC.get("conversationId");
            userId = org.slf4j.MDC.get("userId");
            String bv = org.slf4j.MDC.get("botVersion");
            if (bv != null) {
                try {
                    botVersion = Integer.parseInt(bv);
                } catch (NumberFormatException _) {
                }
            }
        }

        LogEntry entry = new LogEntry(
                record.getMillis(),
                record.getLevel().getName(),
                loggerName,
                message,
                environment,
                botId,
                botVersion,
                conversationId,
                userId,
                instanceIdProducer.getInstanceId()
        );

        publish(entry);
    }

    /**
     * Publish a log entry to the ring buffer and notify listeners.
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
            } catch (Exception _) {
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

    // ==================== Visible for Testing ====================

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

    // ==================== Private Helpers ====================

    /**
     * Format a LogRecord's message, resolving {0},{1}... placeholders
     * from the record's parameters array using a standard Formatter.
     * Avoids deprecated ExtLogRecord.getFormattedMessage().
     */
    private static String formatRecord(java.util.logging.LogRecord record) {
        String msg = record.getMessage();
        if (msg == null) return "";

        // Resolve {0}, {1}, ... placeholders using MessageFormat
        Object[] params = record.getParameters();
        if (params != null && params.length > 0) {
            try {
                return java.text.MessageFormat.format(msg, params);
            } catch (Exception _) {
                return msg; // fallback to raw pattern
            }
        }
        return msg;
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
}
