/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures what a class actually wrote to its logger, for tests that assert on
 * a log line rather than on a return value.
 *
 * <p>
 * Extracted from {@code CapabilityRegistryServiceTest}, which grew the idiom
 * first and now calls it here; the log-injection regression tests in
 * {@code AgentSigningServiceTest}, {@code RestAgentStoreTest} and
 * {@code RestWorkflowStoreTest} need the same thing.
 * </p>
 */
public final class LogCaptureSupport {

    /**
     * A forged log record, as a caller would supply it: the CRLF closes the real
     * record and the remainder reads as a second, server-authored line. Shared so
     * the CWE-117 regression tests all drive the same payload.
     */
    public static final String FORGED_RECORD = "\r\n2026-01-01 00:00:00,000 INFO  [io.quarkus] Forged admin login succeeded";

    private LogCaptureSupport() {
    }

    /**
     * Runs {@code body} with a JUL handler attached to {@code loggerClass}'s logger
     * and returns every message and message parameter it emitted.
     *
     * <p>
     * Both halves matter: JBoss Logging's {@code warnf}/{@code infof} keep the
     * format string as the record's message and hand the interpolated values over
     * as record PARAMETERS, so a test that only read {@code getMessage()} would
     * never see the argument it is asserting about.
     * </p>
     *
     * <p>
     * {@code logging.properties} turns the whole {@code ai.labs.eddi} namespace OFF
     * for plain unit tests, so the logger has to be opened explicitly to see
     * anything; the previous level is always put back.
     * </p>
     */
    public static List<String> captureLogsOf(Class<?> loggerClass, Runnable body) {
        List<String> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(String.valueOf(record.getMessage()));
                if (record.getParameters() != null) {
                    for (Object parameter : record.getParameters()) {
                        captured.add(String.valueOf(parameter));
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        Logger julLogger = Logger.getLogger(loggerClass.getName());
        Level previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            body.run();
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previousLevel);
        }
        return captured;
    }

    /**
     * Asserts that nothing in {@code captured} carries a record boundary — the
     * whole point of {@link LogSanitizer#sanitize}. Kept here so the three
     * regression tests phrase the failure identically.
     *
     * @param captured
     *            everything {@link #captureLogsOf} collected
     * @param what
     *            names the log call under test, so a failure says which one leaked
     */
    public static void assertNoForgedRecordBoundary(List<String> captured, String what) {
        for (String value : captured) {
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new AssertionError("a CR/LF reached the log unsanitized from " + what
                        + ", so a caller can forge log records (CWE-117); offending value: " + quoted(value)
                        + "; everything captured: " + captured);
            }
        }
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\r", "<CR>").replace("\n", "<LF>") + "\"";
    }
}
