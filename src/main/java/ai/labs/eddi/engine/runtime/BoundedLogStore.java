/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.engine.model.LogEntry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
     * How long after a GDPR erasure this node keeps replacing the erased user's id
     * in new log entries — the window in which work the erasure cancelled is still
     * unwinding and logging. The same hour the audit ledger uses.
     */
    static final Duration ERASED_USER_REWRITE_WINDOW = Duration.ofHours(1);

    /**
     * Replacement on a node that hears of an erasure only by hash (cluster mode):
     * it cannot compute the keyed pseudonym, which needs the raw id.
     */
    static final String REMOTE_ERASURE_MARKER = "gdpr-erased";

    /**
     * {@code KvKeys.sha256(userId)} of a recently erased user → what replaces the
     * id and until when. One map for erasures on this node and on others, keyed by
     * the hash either way, so the store never holds an erased user's raw id — not
     * even for the hour the mark lives, nor after it expired and before it is
     * pruned.
     */
    private final Map<String, Erasure> erasedUserHashes = new ConcurrentHashMap<>();

    /**
     * When the earliest mark in {@link #erasedUserHashes} expires; {@code null}
     * while there is none. Lets {@link #publish} prune expired marks with one
     * comparison instead of a scan.
     */
    private volatile Instant nextMarkExpiry;

    private record Erasure(String replacement, Instant until) {
        boolean local() {
            return !REMOTE_ERASURE_MARKER.equals(replacement);
        }
    }

    /**
     * GDPR Art. 17 on this node: replaces {@code userId} with {@code pseudonym} in
     * every entry the ring buffer holds (served by the log REST endpoint and the
     * MCP log tools) and in every entry still waiting for the database writer, and
     * for {@link #ERASED_USER_REWRITE_WINDOW} in every entry logged from now on.
     * <p>
     * The erasure pseudonymises the persisted logs once; entries still queued at
     * that moment, and entries cancelled work logs while it unwinds, used to reach
     * the database afterwards with the raw id, and the ring buffer kept serving it
     * until it was pushed out.
     *
     * @return how many buffered entries were rewritten
     */
    public int markUserErased(String userId, String pseudonym) {
        if (userId == null || pseudonym == null) {
            return 0;
        }
        return mark(KvKeys.sha256(userId), pseudonym, null);
    }

    /**
     * As {@link #markUserErased} for an erasure that started on another node, which
     * names the user only by {@code KvKeys.sha256(userId)}. The id is replaced by
     * {@value #REMOTE_ERASURE_MARKER}; a local mark for the same user (this node
     * erased them too) keeps its keyed pseudonym, and only its lifetime is
     * extended.
     */
    public int markUserErasedHash(String userIdHash) {
        if (userIdHash == null || userIdHash.isBlank()) {
            return 0;
        }
        return mark(userIdHash, REMOTE_ERASURE_MARKER, null);
    }

    /**
     * Sets the mark, then rewrites what is already held: the ring buffer and the
     * entries waiting for the database writer.
     * <p>
     * Synchronized on the store — the monitor {@link #flushToDb} holds — so a flush
     * that drained entries before the mark is finished (and in the database, ahead
     * of the erasure's own sweep of the persisted logs) before the queue is
     * rewritten, and no flush runs while it is. Under the write lock as well, so no
     * publisher adds meanwhile. {@code flushToDb} never takes the write lock and
     * {@code publish} never takes the monitor, so the two cannot deadlock.
     * <p>
     * The mark's lifetime is computed only once both are held: a flush that kept
     * the monitor for longer than {@link #ERASED_USER_REWRITE_WINDOW} would
     * otherwise hand over a mark that had already expired. And the rewrite of what
     * is held does not consult the lifetime at all — it replaces the id of the user
     * being marked, unconditionally — so an entry that waited in the queue longer
     * than a mark lives still leaves pseudonymised.
     *
     * @param untilOverride
     *            the mark's expiry, or {@code null} for now plus the window (tests
     *            pass one)
     */
    private synchronized int mark(String hash, String replacement, Instant untilOverride) {
        int rewritten = 0;
        lock.writeLock().lock();
        try {
            Instant now = clock.instant();
            Instant until = untilOverride != null ? untilOverride : now.plus(ERASED_USER_REWRITE_WINDOW);
            Erasure placed = erasedUserHashes.merge(hash, new Erasure(replacement, until),
                    (existing, added) -> existing.local() && !added.local() ? new Erasure(existing.replacement(), added.until()) : added);
            String effective = placed.replacement();
            pruneExpiredMarks(now);

            // Hash each distinct id once per pass, however many entries carry it.
            Map<String, Boolean> matches = new HashMap<>();
            List<LogEntry> kept = new ArrayList<>(buffer.size());
            for (LogEntry entry : buffer) {
                LogEntry replaced = replaceIfMarked(entry, hash, effective, matches);
                if (replaced != entry) {
                    rewritten++;
                }
                kept.add(replaced);
            }
            if (rewritten > 0) {
                buffer.clear();
                buffer.addAll(kept);
            }
            // Same order, same count: dbQueued stays right.
            for (int i = dbQueue.size(); i > 0; i--) {
                LogEntry queued = dbQueue.poll();
                if (queued == null) {
                    break;
                }
                dbQueue.offer(replaceIfMarked(queued, hash, effective, matches));
            }
        } finally {
            lock.writeLock().unlock();
        }
        return rewritten;
    }

    /**
     * {@code entry} with {@code replacement} for its user id when that id hashes to
     * {@code hash}.
     */
    private static LogEntry replaceIfMarked(LogEntry entry, String hash, String replacement, Map<String, Boolean> matches) {
        if (entry == null || entry.userId() == null) {
            return entry;
        }
        if (!matches.computeIfAbsent(entry.userId(), id -> hash.equals(KvKeys.sha256(id)))) {
            return entry;
        }
        return withUserId(entry, replacement);
    }

    private static LogEntry withUserId(LogEntry entry, String userId) {
        return new LogEntry(entry.timestamp(), entry.level(), entry.loggerName(), entry.message(), entry.environment(), entry.agentId(),
                entry.agentVersion(), entry.conversationId(), userId, entry.instanceId());
    }

    /**
     * Drops the marks that expired and recomputes {@link #nextMarkExpiry}. Cheap
     * enough for {@link #publish} to call whenever the earliest mark is due.
     */
    private void pruneExpiredMarks(Instant now) {
        erasedUserHashes.values().removeIf(erasure -> erasure.until().isBefore(now));
        nextMarkExpiry = erasedUserHashes.values().stream().map(Erasure::until).min(Instant::compareTo).orElse(null);
    }

    /** The time source of the mark lifetimes; replaceable in tests. */
    private volatile Clock clock = Clock.systemUTC();

    /** Test hook: the clock mark lifetimes are measured on. */
    void setClockForTesting(Clock testClock) {
        this.clock = testClock;
    }

    /** Test hook: the keys of the erasure marks held. */
    Set<String> markKeys() {
        return Set.copyOf(erasedUserHashes.keySet());
    }

    /** Test hook: as if every mark had expired and been pruned. */
    void dropMarksForTesting() {
        erasedUserHashes.clear();
        nextMarkExpiry = null;
    }

    /** Test hook: a local mark with an explicit expiry. */
    int markUserErasedUntil(String userId, String pseudonym, Instant until) {
        return mark(KvKeys.sha256(userId), pseudonym, until);
    }

    /**
     * The entry with an erased user's id replaced, or the same instance when it
     * names no erased user. Hashes nothing while no mark is held, so the hot path
     * of {@link #publish} costs one emptiness check.
     */
    LogEntry scrub(LogEntry entry) {
        if (entry == null || entry.userId() == null || erasedUserHashes.isEmpty()) {
            return entry;
        }
        Erasure erasure = erasedUserHashes.get(KvKeys.sha256(entry.userId()));
        if (erasure == null || erasure.until().isBefore(clock.instant())) {
            return entry;
        }
        return withUserId(entry, erasure.replacement());
    }

    /**
     * Publish a log entry to the ring buffer and notify listeners.
     */
    public void publish(LogEntry entry) {
        // The scrub runs under the same write lock that adds the entry to the buffer
        // and the DB queue. An erasure puts its mark into the map first and only then
        // takes this lock to rewrite the buffer, so for any entry published at the same
        // moment one of two orders holds: this critical section ran first — the raw
        // entry is in the buffer and the DB queue when the rewrite reads them — or it
        // ran after, and scrub() sees the mark.
        // Scrubbing before taking the lock left a gap between the two in which a raw
        // entry could land after the rewrite. scrub() hashes nothing while no erasure
        // is recent, so the critical section stays as cheap as the plain add.
        LogEntry published;
        lock.writeLock().lock();
        try {
            Instant due = nextMarkExpiry;
            if (due != null) {
                // Only while a mark is held: expired marks go on the first publish
                // after they expire, not at the next erasure.
                Instant now = clock.instant();
                if (due.isBefore(now)) {
                    pruneExpiredMarks(now);
                }
            }
            published = scrub(entry);
            if (buffer.size() >= bufferSize) {
                buffer.pollFirst(); // evict oldest
            }
            buffer.addLast(published);

            // Enqueue for DB persistence
            if (dbEnabled && meetsMinLevel(published.level())) {
                if (dbQueued.incrementAndGet() <= maxDbQueue) {
                    dbQueue.offer(published);
                } else {
                    // Not logged here: this runs inside the logging pipeline.
                    dbQueued.decrementAndGet();
                    dbDropped.incrementAndGet();
                }
            }
        } finally {
            lock.writeLock().unlock();
        }

        // Notify SSE listeners (non-blocking)
        for (Consumer<LogEntry> listener : listeners.values()) {
            try {
                listener.accept(published);
            } catch (Exception _) {
                // Don't let a bad listener break logging
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
     * <p>
     * Synchronized so an erasure mark ({@link #markUserErased}) can wait for a
     * batch that was drained before an erasure marked its user and may still be
     * committing.
     */
    synchronized void flushToDb() {
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
                // Queued before an erasure of its user: leaves with the pseudonym.
                batch.add(scrub(entry));
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
