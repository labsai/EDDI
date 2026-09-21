/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import io.quarkus.logging.LoggingFilter;

import java.util.logging.Filter;
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
        LogCaptureFilter.staticStore = store;
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
