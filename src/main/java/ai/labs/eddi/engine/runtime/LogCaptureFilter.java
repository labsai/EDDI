/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import io.quarkus.logging.LoggingFilter;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.ExtLogRecord.FormatStyle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Quarkus-native logging filter that intercepts every log record in the JBoss
 * LogManager workflow and captures it into the {@link BoundedLogStore} ring
 * buffer.
 *
 * <p>
 * This filter always returns {@code true} (it never suppresses logs). It has
 * two side effects: it rewrites the record in place via
 * {@link LogRecordRedactor} — stripping credential material and escaping
 * anything that could forge a record boundary — so console output matches the
 * ring buffer, and it pushes each record, with the text the redactor already
 * produced, to {@link BoundedLogStore#capture(LogRecord, String)} for
 * ring-buffer storage and SSE streaming.
 * </p>
 *
 * <h3>This filter is EDDI's only CWE-117 guarantee for throwables</h3>
 * <p>
 * The console format ends in {@code %s%e}, and {@code %e} renders a stack trace
 * whose first line is the throwable's own {@code toString()}. Call-site
 * {@code LogSanitizer.sanitize(…)} covers the {@code %s} half and cannot cover
 * the other, so the 400-odd log calls that pass a throwable rely on the rewrite
 * this filter performs. It is wired to the console handler alone, through
 * {@code quarkus.log.console.filter=eddi-log-capture}; enabling a file or
 * syslog handler means wiring this filter to it as well, and
 * {@code LogRecordBoundaryForgeryTest} fails if either that property or the
 * format string moves out from under this claim.
 * </p>
 *
 * <h3>Bootstrap Safety</h3>
 * <p>
 * Quarkus creates {@code @LoggingFilter} beans during static initialization,
 * before the CDI (ArC) container is fully ready. If this filter used
 * constructor injection for {@link BoundedLogStore}, the CDI proxy would throw
 * {@link IllegalStateException}. Instead, the store is resolved lazily on first
 * successful access via {@link io.quarkus.arc.Arc#container()}, making the
 * filter safe to use from the very earliest log records.
 * </p>
 * <p>
 * The store is created eagerly at application startup ({@code @Startup} on
 * {@link BoundedLogStore}), but a few records are always logged before that
 * point. Rather than drop them, the filter holds the <em>redacted</em> form of
 * each in a small bounded buffer ({@value #PRE_START_CAPACITY} records, newest
 * kept, overflow counted) and replays it into the store, oldest first, when
 * {@link #setStore(BoundedLogStore)} is called; a synthetic WARN records how
 * many were dropped, if any. Only records whose redaction succeeded are held —
 * a record that failed redaction is never buffered raw, it is stripped
 * ({@link LogRecordRedactor#failClosed(LogRecord)}) exactly as before.
 * Buffering never suppresses or alters console output, never throws and never
 * waits on the store. Records racing with the replay may be stored slightly out
 * of order relative to it.
 * </p>
 *
 * <h3>Quarkus Integration</h3>
 * <p>
 * Configured in {@code application.properties}:
 * </p>
 *
 * <pre>
 * quarkus.log.console.filter = eddi - log - capture
 * </pre>
 *
 * @author ginccc
 * @since 6.0.0
 * @see BoundedLogStore#capture(LogRecord, String)
 */
@LoggingFilter(name = "eddi-log-capture")
public final class LogCaptureFilter implements Filter {

    /**
     * Statically registered reference to the BoundedLogStore CDI bean. Populated by
     * BoundedLogStore during its @PostConstruct phase.
     */
    private static volatile BoundedLogStore staticStore;

    /**
     * Registers the active BoundedLogStore.
     *
     * @param store
     *            the fully initialized store proxy
     */
    public static void setStore(BoundedLogStore store) {
        List<ExtLogRecord> early;
        long dropped;
        synchronized (PRE_START) {
            early = new ArrayList<>(PRE_START);
            PRE_START.clear();
            dropped = preStartDropped;
            preStartDropped = 0;
            // Publish under the lock: a logger that finds the store null and then
            // takes the lock sees it set and captures directly, never buffering
            // into a queue nobody will drain again.
            LogCaptureFilter.staticStore = store;
        }
        if (store == null) {
            return;
        }
        try {
            if (dropped > 0) {
                ExtLogRecord note = new ExtLogRecord(Level.WARNING, "", FormatStyle.NO_FORMAT, LogCaptureFilter.class.getName());
                note.setLoggerName(LogCaptureFilter.class.getName());
                if (!early.isEmpty()) {
                    note.setInstant(early.get(0).getInstant());
                }
                store.capture(note, dropped + " log record(s) logged before the log store started were dropped (pre-start buffer holds "
                        + PRE_START_CAPACITY + ")");
            }
            for (ExtLogRecord r : early) {
                store.capture(r, r.getMessage());
            }
        } catch (Exception _) {
            // Replay is best effort; it must never break startup
        }
    }

    /** Most records held while no store is registered. */
    static final int PRE_START_CAPACITY = 1000;

    private static final ArrayDeque<ExtLogRecord> PRE_START = new ArrayDeque<>();
    private static long preStartDropped;

    /** Visible for testing: the number of records currently held. */
    static int preStartSize() {
        synchronized (PRE_START) {
            return PRE_START.size();
        }
    }

    /**
     * Holds an immutable, already-redacted copy of {@code record} until a store is
     * registered. Returns the store if one appeared meanwhile, so the caller
     * captures directly.
     */
    private static BoundedLogStore holdUntilStoreExists(LogRecord record, String redactedMessage) {
        try {
            ExtLogRecord copy = new ExtLogRecord(record.getLevel(), redactedMessage, FormatStyle.NO_FORMAT, LogCaptureFilter.class.getName());
            copy.setLoggerName(record.getLoggerName());
            copy.setInstant(record.getInstant());
            if (record instanceof ExtLogRecord ext) {
                Map<String, String> mdc = ext.getMdcCopy();
                if (mdc != null) {
                    mdc.forEach(copy::putMdc);
                }
            }
            synchronized (PRE_START) {
                if (staticStore != null) {
                    return staticStore;
                }
                if (PRE_START.size() >= PRE_START_CAPACITY) {
                    PRE_START.pollFirst();
                    preStartDropped++;
                }
                PRE_START.addLast(copy);
            }
        } catch (Exception _) {
            // Never let buffering break a log line
        }
        return null;
    }

    /**
     * The text {@link BoundedLogStore#capture} would store for a record it is
     * handed without a message, or null when even that cannot be produced.
     */
    private static String preStartMessage(LogRecord record) {
        try {
            return LogRecordRedactor.rewrite(BoundedLogStore.formatRecord(record));
        } catch (Exception _) {
            return null;
        }
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        // Redact BEFORE the console handler formats the record, not on a copy
        // afterwards. Redacting on a copy is what left container stdout — the one
        // destination an operator cannot revoke after the fact — carrying the
        // credentials the ring buffer, the database and the SSE stream were all
        // already stripped of.
        String redactedMessage = null;
        boolean redacted = false;
        try {
            // The redacted text is carried over to capture(), which would
            // otherwise format and redact the same record a second time — a full
            // second pass of the rules, on the thread emitting the line, that
            // could not change anything the first pass had already done.
            redactedMessage = LogRecordRedactor.redact(record).message();
            redacted = true;
        } catch (Exception _) {
            // A redaction failure must never suppress or break a log line, and
            // must never publish one either. The null message below sends
            // BoundedLogStore back through its own format and redact, so the ring
            // buffer stays clean, and the record itself is stripped afterwards so
            // the console handler cannot print what this pass never removed.
        }

        BoundedLogStore store = staticStore;
        if (store == null && redacted) {
            String held = redactedMessage;
            if (held == null) {
                // Redaction succeeded but left the message to capture() — a plain JUL
                // record still carrying parameters, which capture() formats printf-first.
                // The held copy is NO_FORMAT, so it is formatted and redacted the same way
                // here, or the record would be dropped only because the store did not
                // exist yet.
                held = preStartMessage(record);
            }
            if (held != null && !held.isEmpty()) {
                store = holdUntilStoreExists(record, held);
            }
        }
        if (store != null) {
            try {
                store.capture(record, redactedMessage);
            } catch (Exception _) {
                // Ignore errors during hot-reload or shutdown
            }
        }

        if (!redacted) {
            // After capture(), deliberately. The store redacts its own copy from
            // the record's raw message and parameters, so the ring buffer keeps a
            // fully formatted line; the record is only stripped once that copy has
            // been taken, and stripping is what the console handler — which
            // formats this same record next — needs to have happened.
            LogRecordRedactor.failClosed(record);
        }

        // Always return true — we never suppress log records
        return true;
    }
}
