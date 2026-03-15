package ai.labs.eddi.engine.runtime;

import io.quarkus.logging.LoggingFilter;
import jakarta.inject.Inject;

import java.util.logging.Filter;
import java.util.logging.LogRecord;

/**
 * Quarkus-native logging filter that intercepts every log record
 * in the JBoss LogManager pipeline and captures it into the
 * {@link BoundedLogStore} ring buffer.
 *
 * <p>This filter always returns {@code true} (it never suppresses logs).
 * Its only purpose is to tap into the logging pipeline as a side effect,
 * pushing each record to the {@link BoundedLogStore#capture(LogRecord)}
 * method for ring-buffer storage and SSE streaming.</p>
 *
 * <h3>Quarkus Integration</h3>
 * <p>Configured in {@code application.properties}:</p>
 * <pre>
 * quarkus.log.console.filter=eddi-log-capture
 * </pre>
 *
 * @author ginccc
 * @since 6.0.0
 * @see BoundedLogStore#capture(LogRecord)
 */
@LoggingFilter(name = "eddi-log-capture")
public final class LogCaptureFilter implements Filter {

    private final BoundedLogStore store;

    @Inject
    public LogCaptureFilter(BoundedLogStore store) {
        this.store = store;
    }

    @Override
    public boolean isLoggable(LogRecord record) {
        // Side-effect: capture the record into the ring buffer
        store.capture(record);
        // Always return true — we never suppress log records
        return true;
    }
}
