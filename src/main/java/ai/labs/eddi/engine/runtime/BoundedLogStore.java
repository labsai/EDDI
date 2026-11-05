/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.LogEntry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;

/**
 * In-memory ring buffer that captures log records with MDC context. Provides:
 * <ul>
 * <li>Instant in-memory access for the SSE live-tail endpoint</li>
 * <li>Listener-based push for connected SSE clients</li>
 * <li>Async batched DB persistence via {@link IDatabaseLogs}</li>
 * </ul>
 *
 * <p>
 * Log records are captured via {@link LogCaptureFilter}, a Quarkus
 * {@code @LoggingFilter} that intercepts every log record in the JBoss
 * LogManager workflow and pushes it to this store.
 * </p>
 *
 * <p>
 * {@code @Startup}: the store must exist from application start. It used to be
 * created on first injection (by the log admin REST resource or the MCP tools),
 * and until then {@link LogCaptureFilter} had nowhere to send records, so a
 * fresh instance showed no logs — and persisted none — until someone opened the
 * log view.
 * </p>
 *
 * @author ginccc
 * @since 6.0.0
 */
@Startup
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

    /**
     * Cap on entries waiting for the DB writer. The queue was unbounded: with the
     * database slow or down (every flush failing, or one flush stuck) a burst of
     * WARN/ERROR records — which a failing database itself produces — grew it
     * without limit, until the heap went. Past the cap new entries are dropped and
     * counted; the ring buffer and the live tail still see them.
     */
    static final int DEFAULT_MAX_DB_QUEUE = 50_000;
    private final int maxDbQueue;
    private final AtomicInteger dbQueued = new AtomicInteger();
    private final AtomicLong dbDropped = new AtomicLong();
    private ScheduledExecutorService dbWriter;

    @Inject
    public BoundedLogStore(InstanceIdProducer instanceIdProducer, IDatabaseLogs databaseLogs,
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
        this.maxDbQueue = DEFAULT_MAX_DB_QUEUE;
    }

    private BoundedLogStore(InstanceIdProducer instanceIdProducer, IDatabaseLogs databaseLogs, int bufferSize, boolean dbEnabled,
            int dbFlushIntervalSeconds, String dbPersistMinLevel, int maxDbQueue) {
        this.instanceIdProducer = instanceIdProducer;
        this.databaseLogs = databaseLogs;
        this.bufferSize = bufferSize;
        this.dbEnabled = dbEnabled;
        this.dbFlushIntervalSeconds = dbFlushIntervalSeconds;
        this.dbPersistMinLevel = dbPersistMinLevel.toUpperCase();
        this.buffer = new ArrayDeque<>(bufferSize);
        this.maxDbQueue = maxDbQueue;
    }

    /**
     * As
     * {@link #createForTesting(InstanceIdProducer, IDatabaseLogs, int, boolean, int, String)},
     * with a DB queue cap.
     */
    static BoundedLogStore createForTesting(InstanceIdProducer instanceIdProducer, IDatabaseLogs databaseLogs, int bufferSize, boolean dbEnabled,
                                            int dbFlushIntervalSeconds, String dbPersistMinLevel, int maxDbQueue) {
        return new BoundedLogStore(instanceIdProducer, databaseLogs, bufferSize, dbEnabled, dbFlushIntervalSeconds, dbPersistMinLevel,
                maxDbQueue);
    }

    /**
     * Factory method for testing — creates a store without CDI. Call
     * {@link #init()} after construction to start the DB writer.
     */
    static BoundedLogStore createForTesting(InstanceIdProducer instanceIdProducer, IDatabaseLogs databaseLogs, int bufferSize, boolean dbEnabled,
                                            int dbFlushIntervalSeconds, String dbPersistMinLevel) {
        return new BoundedLogStore(instanceIdProducer, databaseLogs, bufferSize, dbEnabled, dbFlushIntervalSeconds, dbPersistMinLevel);
    }

    private volatile boolean initialized;

    @PostConstruct
    void init() {
        if (initialized)
            return;
        initialized = true;

        // Register with the global logging filter BEFORE emitting internal logs
        LogCaptureFilter.setStore(this);

        // Start async DB writer if enabled
        if (dbEnabled) {
            dbWriter = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "log-db-writer");
                t.setDaemon(true);
                return t;
            });
            dbWriter.scheduleAtFixedRate(this::flushToDb, dbFlushIntervalSeconds, dbFlushIntervalSeconds, TimeUnit.SECONDS);
            log.infov("BoundedLogStore: DB persistence enabled (flush every {0}s, min level: {1})", dbFlushIntervalSeconds, dbPersistMinLevel);
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
     * Called by {@link LogCaptureFilter} for every log record passing through the
     * Quarkus logging workflow.
     *
     * @param record
     *            the JUL LogRecord (actually a JBoss ExtLogRecord at runtime)
     */
    public void capture(LogRecord record) {
        capture(record, null);
    }

    /**
     * Capture a record whose message has already been formatted and redacted by
     * {@link LogCaptureFilter}.
     *
     * @param record
     *            the JUL LogRecord (actually a JBoss ExtLogRecord at runtime)
     * @param preRedactedMessage
     *            the record's redacted message, or {@code null} to format and
     *            redact it here — which is what happens when redaction upstream
     *            threw, so the ring buffer is never fed an unscanned message
     */
    public void capture(LogRecord record, String preRedactedMessage) {
        if (record == null)
            return;

        String message = preRedactedMessage;
        if (message == null) {
            // Format the message using a Formatter (avoids deprecated
            // getFormattedMessage())
            message = formatRecord(record);
            if (message == null || message.isEmpty())
                return;

            // Redact potential secrets from log messages, and escape anything that
            // could end a record, exactly as LogRecordRedactor would have. The ring
            // buffer is read back through the admin log API and the SSE live tail,
            // so a forged boundary kept here forges an entry there too.
            message = LogRecordRedactor.rewrite(message);
        }
        if (message.isEmpty())
            return;

        // Don't capture our own log messages to avoid infinite recursion
        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.startsWith("ai.labs.eddi.engine.runtime.BoundedLogStore")) {
            return;
        }

        // Read MDC context from ExtLogRecord if available, otherwise from SLF4J MDC
        String environment = null;
        String agentId = null;
        String conversationId = null;
        String userId = null;
        Integer agentVersion = null;

        if (record instanceof ExtLogRecord extRecord) {
            environment = extRecord.getMdc("environment");
            agentId = extRecord.getMdc("agentId");
            conversationId = extRecord.getMdc("conversationId");
            userId = extRecord.getMdc("userId");
            String bv = extRecord.getMdc("agentVersion");
            if (bv != null) {
                try {
                    agentVersion = Integer.parseInt(bv);
                } catch (NumberFormatException _) {
                }
            }
        } else {
            // Fallback: read from SLF4J MDC
            environment = org.slf4j.MDC.get("environment");
            agentId = org.slf4j.MDC.get("agentId");
            conversationId = org.slf4j.MDC.get("conversationId");
            userId = org.slf4j.MDC.get("userId");
            String bv = org.slf4j.MDC.get("agentVersion");
            if (bv != null) {
                try {
                    agentVersion = Integer.parseInt(bv);
                } catch (NumberFormatException _) {
                }
            }
        }

        LogEntry entry = new LogEntry(record.getMillis(), record.getLevel().getName(), loggerName, message, environment, agentId, agentVersion,
                conversationId, userId, instanceIdProducer.getInstanceId());

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
            if (dbQueued.incrementAndGet() <= maxDbQueue) {
                dbQueue.offer(entry);
            } else {
                // Not logged here: this runs inside the logging pipeline.
                dbQueued.decrementAndGet();
                dbDropped.incrementAndGet();
            }
        }
    }

    /**
     * Get entries from the ring buffer, optionally filtered.
     */
    public List<LogEntry> getEntries(String agentId, String conversationId, String level, int limit) {
        lock.readLock().lock();
        try {
            List<LogEntry> result = new ArrayList<>();
            // Iterate newest-first (from tail)
            Iterator<LogEntry> it = buffer.descendingIterator();
            while (it.hasNext() && result.size() < limit) {
                LogEntry entry = it.next();
                if (matchesFilter(entry, agentId, conversationId, level)) {
                    result.add(entry);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if a log entry's level meets the given minimum level threshold. Used by
     * both the ring buffer query and SSE stream filter.
     *
     * @param entryLevel
     *            the log entry's level (e.g. "INFO")
     * @param minLevel
     *            the minimum level threshold (e.g. "WARN")
     * @return true if entryLevel >= minLevel
     */
    public boolean meetsMinimumLevel(String entryLevel, String minLevel) {
        return levelOrdinal(entryLevel) >= levelOrdinal(minLevel);
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
        if (dbQueue.isEmpty())
            return;

        long dropped = dbDropped.getAndSet(0);
        if (dropped > 0) {
            log.warnv("Dropped {0} log entries before DB persistence: more than {1} were waiting (the database is slow or down)", dropped,
                    maxDbQueue);
        }
        // Batches of a fixed size, and no more entries than were queued when this flush
        // began:
        // producers refill the slots a drain frees, so draining "until empty" could
        // build one batch
        // far beyond the queue cap (or never finish).
        int remaining = dbQueue.size();
        while (remaining > 0) {
            List<LogEntry> batch = new ArrayList<>(Math.min(remaining, FLUSH_BATCH_SIZE));
            LogEntry entry;
            while (batch.size() < FLUSH_BATCH_SIZE && remaining > 0 && (entry = dbQueue.poll()) != null) {
                dbQueued.decrementAndGet();
                batch.add(entry);
                remaining--;
            }
            if (batch.isEmpty()) {
                return;
            }
            try {
                databaseLogs.addLogsBatch(batch);
            } catch (Exception e) {
                log.errorv("Failed to flush {0} log entries to DB: {1}", batch.size(), e.getMessage());
            }
        }
    }

    /** The most entries handed to the database in one batch. */
    static final int FLUSH_BATCH_SIZE = 5_000;

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
        return dbQueued.get();
    }

    // ==================== Private Helpers ====================

    /**
     * Format a LogRecord's message, resolving placeholders from the record's
     * parameters array.
     *
     * JBoss LogManager's ExtLogRecord supports both {@code {0},{1}...}
     * (MessageFormat) and {@code %s,%d...} (printf) styles via
     * {@link org.jboss.logmanager.ExtLogRecord#getFormattedMessage()}. For plain
     * JUL LogRecords, we fall back to manual MessageFormat.
     */
    static String formatRecord(LogRecord record) {
        String msg = record.getMessage();
        if (msg == null)
            return "";

        Object[] params = record.getParameters();

        // 1. Try ExtLogRecord's built-in getFormattedMessage() first
        if (record instanceof ExtLogRecord extRecord) {
            try {
                String formatted = extRecord.getFormattedMessage();
                if (formatted != null && !formatted.equals(msg)) {
                    return formatted;
                }
            } catch (Exception _) {
                // fall through to manual formatting
            }
        }

        // 2. If getFormattedMessage() returned raw msg or wasn't ExtLogRecord, format
        // manually
        if (params != null && params.length > 0) {
            // Any '%' is enough to attempt printf — String.format throws on a
            // malformed pattern and we fall through, so it can decide for itself.
            //
            // Enumerating specifiers missed every indexed, padded or grouped form:
            // %1$s, %02d, %,d, %5.2f, %b, %e. Those matter because the MessageFormat
            // attempt below does NOT throw on them — with no {0} placeholders it
            // returns the pattern unchanged, so the String.format fallback further
            // down is never reached and the raw "%1$s" is what reaches the viewer.
            if (msg.indexOf('%') >= 0) {
                try {
                    return String.format(msg, params);
                } catch (Exception _) {
                    // ignore and try MessageFormat
                }
            }

            // Try MessageFormat ({0}, {1}, etc.)
            try {
                return java.text.MessageFormat.format(msg, params);
            } catch (Exception _) {
                // fall through to String.format fallback
            }

            // Fallback: try String.format
            try {
                return String.format(msg, params);
            } catch (Exception _) {
                return msg;
            }
        }

        return msg;
    }

    private boolean matchesFilter(LogEntry entry, String agentId, String conversationId, String level) {
        if (agentId != null && !agentId.equals(entry.agentId()))
            return false;
        if (conversationId != null && !conversationId.equals(entry.conversationId()))
            return false;
        if (level != null && !meetsMinimumLevel(entry.level(), level))
            return false;
        return true;
    }

    private boolean meetsMinLevel(String level) {
        if (level == null)
            return false;
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
