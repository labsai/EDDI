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
 * This filter always returns {@code true} (it never suppresses logs). Its only
 * purpose is to tap into the logging workflow as a side effect, pushing each
 * record to the {@link BoundedLogStore#capture(LogRecord)} method for
 * ring-buffer storage and SSE streaming.
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
 * @see BoundedLogStore#capture(LogRecord)
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
        BoundedLogStore store = staticStore;
        if (store != null) {
            try {
                store.capture(record);
            } catch (Exception _) {
                // Ignore errors during hot-reload or shutdown
            }
        }

        // Always return true — we never suppress log records
        return true;
    }
}
