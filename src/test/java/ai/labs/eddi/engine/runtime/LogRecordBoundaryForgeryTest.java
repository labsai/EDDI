/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.utils.LogCaptureSupport;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.ExtLogRecord.FormatStyle;
import org.jboss.logmanager.formatters.PatternFormatter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * CWE-117 regression tests for the half of a log line that no call site can
 * reach: the throwable.
 *
 * <p>
 * {@code quarkus.log.console.format} ends in {@code %s%e}. {@code %e} renders a
 * stack trace whose FIRST line is the throwable's own {@code toString()} —
 * {@code ClassName: message} — so an attacker-controlled CR/LF inside an
 * exception message ended the record and everything after it read as a second,
 * server-authored entry. That happened however carefully the message half was
 * sanitized, which is why {@code LogSanitizer.sanitize(…)} at 400-odd call
 * sites could not close it.
 * </p>
 *
 * <p>
 * These tests therefore assert on RENDERED formatter output rather than on what
 * a logger was handed: {@code LogCaptureSupport.captureLogsOf} reads
 * {@code getMessage()} and {@code getParameters()} but not {@code getThrown()},
 * so it cannot see this defect at all. The formatter is built from the real
 * pattern read out of {@code src/main/resources/application.properties}, so the
 * tests grade the line an operator actually sees.
 * </p>
 *
 * <p>
 * The invariant every test shares: after the first, every line of a rendered
 * record must be a continuation the JDK generated — indented with a TAB, or a
 * {@code Caused by:} the JVM wrote. Nothing an attacker supplied may start a
 * line, because a line it starts is a record it authored.
 * </p>
 */
@DisplayName("a forged record boundary cannot escape a rendered log line")
class LogRecordBoundaryForgeryTest {

    /**
     * The payload, as a caller would supply it: CRLF closes the real record and the
     * rest reads as a genuine INFO line. Shared with the message-level regression
     * tests so every CWE-117 test in the tree drives the same string.
     */
    private static final String FORGED = LogCaptureSupport.FORGED_RECORD;

    /** The text an operator would be fooled by, if any of this leaked. */
    private static final String FORGED_TEXT = "Forged admin login succeeded";

    /**
     * Built at runtime rather than written as a unicode escape in the source: the
     * project formatter turns that escape back into the raw character, and a raw
     * U+2028 sitting in a .java file is a thing reviewers and editors mangle.
     */
    private static final String LINE_SEPARATOR_CHAR = Character.toString(0x2028);

    private static PatternFormatter formatter;

    @BeforeAll
    static void loadTheRealConsolePattern() throws Exception {
        formatter = new PatternFormatter(consoleFormat());
    }

    // ------------------------------------------------------------------
    // the throwable half — what this change exists for
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a CRLF in an exception message does not end the record")
    void exceptionMessageCannotForgeARecord() {
        ExtLogRecord record = warn("Deployment failed for Agent a-1 v1");
        record.setThrown(new IllegalStateException("connect failed" + FORGED));

        String rendered = render(record);

        assertSingleRecord(rendered, "an exception message");
        assertTrue(rendered.contains(FORGED_TEXT),
                "the payload is escaped, not deleted — losing it would cost the diagnostic the stack trace is here for: " + rendered);
        assertTrue(rendered.contains("connect failed\\r\\n2026-01-01"),
                "and it is escaped in place, so the line still reads as the exception's own message: " + rendered);
    }

    @Test
    @DisplayName("a CRLF in a CAUSE's message does not end the record")
    void causeMessageCannotForgeARecord() {
        ExtLogRecord record = warn("outbound call failed");
        record.setThrown(new RuntimeException("wrapper", new IllegalStateException("upstream said" + FORGED)));

        assertSingleRecord(render(record), "a cause's message");
    }

    @Test
    @DisplayName("a CRLF in a SUPPRESSED exception's message does not end the record")
    void suppressedMessageCannotForgeARecord() {
        // try-with-resources around a failed outbound call attaches the close
        // failure as a suppressed exception, and %e prints it exactly like a cause.
        var thrown = new IllegalStateException("request aborted");
        thrown.addSuppressed(new IllegalStateException("while closing" + FORGED));
        ExtLogRecord record = warn("outbound call failed");
        record.setThrown(thrown);

        assertSingleRecord(render(record), "a suppressed exception's message");
    }

    @Test
    @DisplayName("the stack trace stays a stack trace — frames, causes and '... N more' all survive")
    void theStackTraceIsStillReadable() {
        var cause = new IllegalStateException("upstream refused" + FORGED);
        var thrown = new RuntimeException("deployment failed", cause);
        ExtLogRecord record = warn("Deployment failed for Agent a-1 v1");
        record.setThrown(thrown);

        // What the JDK prints for this graph BEFORE anything is substituted. The
        // comparison below is against this rather than against a frame count,
        // because the interesting claim is that substituting the throwable changed
        // the trace in no way at all — same frames, same order, same "... N more"
        // elision, which is computed from the cause's frames and is exactly the
        // sort of thing a careless copy breaks.
        List<String> structureBefore = continuationLinesOf(printStackTrace(thrown));

        String rendered = render(record);

        assertSingleRecord(rendered, "a wrapped exception");
        List<String> lines = linesOf(rendered);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("\tat ai.labs.eddi.engine.runtime.LogRecordBoundaryForgeryTest")),
                "the frames an operator reads must still be there and still be indented: " + rendered);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Caused by: java.lang.IllegalStateException")),
                "and the cause must still render as a cause, with its real type: " + rendered);
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("\t... ")),
                "including the '... N more' elision, which is computed from the cause's frames: " + rendered);
        assertEquals(structureBefore, continuationLinesOf(rendered),
                "and every generated line is identical to what the original threw: " + rendered);
    }

    @Test
    @DisplayName("a Unicode line separator in an exception message does not end the record")
    void unicodeLineSeparatorCannotForgeARecord() {
        // Not a line break to the JVM, but one to JavaScript, to JSON consumers and
        // to several log viewers — which is to say, to the tools logs are read in.
        ExtLogRecord record = warn("import failed");
        record.setThrown(new IllegalStateException("bad config" + LINE_SEPARATOR_CHAR + FORGED_TEXT));

        String rendered = render(record);

        assertFalse(rendered.contains(LINE_SEPARATOR_CHAR), "the separator must not reach the output: " + rendered);
        assertTrue(rendered.contains("\\u2028"), "it is escaped, so the text it carried is still legible: " + rendered);
    }

    // ------------------------------------------------------------------
    // the message half — the same rule, applied once instead of per call site
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a CRLF in the message itself does not end the record")
    void messageTextCannotForgeARecord() {
        assertSingleRecord(render(warn("Agent not found: " + FORGED)), "the message text");
    }

    @Test
    @DisplayName("a CRLF in a format PARAMETER does not end the record — the shape this actually arrives in")
    void formatParameterCannotForgeARecord() {
        // Nobody writes a newline into a format string. It arrives as an argument:
        // LOGGER.warnf("Agent not found: %s", agentId).
        ExtLogRecord record = new ExtLogRecord(Level.WARNING, "Agent not found: %s", FormatStyle.PRINTF, loggerName());
        record.setParameters(new Object[]{"a-1" + FORGED});

        assertSingleRecord(render(record), "a format parameter");
    }

    // ------------------------------------------------------------------
    // and none of it may cost a clean line anything
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a clean record renders byte-for-byte as it did before, and keeps its throwable")
    void leavesACleanRecordCompletelyAlone() {
        var original = new IllegalArgumentException("version must be positive");
        ExtLogRecord record = warn("could not deploy");
        record.setThrown(original);
        String before = formatter.format(record);

        assertFalse(LogRecordRedactor.redactInPlace(record), "nothing here needs rewriting");

        assertEquals(before, formatter.format(record), "a clean line must not change shape because this guard exists");
        assertSame(original, record.getThrown(),
                "and must keep the throwable itself — substituting it would cost identity and the real type for nothing");
    }

    @Test
    @DisplayName("a TAB in a message survives, because a TAB cannot end a record")
    void keepsTabs() {
        String rendered = render(warn("columns:\tid\tname"));

        assertTrue(rendered.contains("columns:\tid\tname"),
                "escaping tabs would cost every rendered stack trace its indentation for no security gain: " + rendered);
    }

    // ------------------------------------------------------------------
    // the configuration this guarantee rests on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the console handler still runs the filter that performs the rewrite")
    void theGuaranteeIsStillWiredUp() throws Exception {
        Properties properties = applicationProperties();

        assertEquals("eddi-log-capture", properties.getProperty("quarkus.log.console.filter"),
                "LogCaptureFilter is where the rewrite happens; detached from the console handler, nothing escapes a"
                        + " throwable's message and every test above passes while production leaks");
        assertTrue(properties.getProperty("quarkus.log.console.format", "").endsWith("%s%e%n"),
                "these tests are written against a format that renders the throwable; if %e moved, re-read them before"
                        + " trusting them — and if a file or syslog handler was added, it needs the same filter");
    }

    // ------------------------------------------------------------------

    /**
     * Rewrites the record exactly as the console handler's filter does, then
     * renders it.
     */
    private static String render(LogRecord record) {
        LogRecordRedactor.redactInPlace(record);
        return formatter.format(record);
    }

    private static ExtLogRecord warn(String message) {
        return new ExtLogRecord(Level.WARNING, message, loggerName());
    }

    private static String loggerName() {
        return LogRecordBoundaryForgeryTest.class.getName();
    }

    /**
     * Splits on anything a log reader treats as a line break — CRLF, bare LF, bare
     * CR — rather than on the platform separator, because a bare CR forging a line
     * in a viewer is the same defect as a CRLF forging one in a file.
     */
    /** Only the TAB-indented lines: the part of a trace the JDK generates. */
    private static List<String> continuationLinesOf(String rendered) {
        return linesOf(rendered).stream().filter(line -> line.startsWith("\t")).toList();
    }

    private static String printStackTrace(Throwable thrown) {
        var printed = new StringWriter();
        thrown.printStackTrace(new PrintWriter(printed));
        return printed.toString();
    }

    private static List<String> linesOf(String rendered) {
        var lines = new ArrayList<String>();
        for (String line : rendered.split("\r\n|\r|\n", -1)) {
            lines.add(line);
        }
        return lines;
    }

    /**
     * The whole security property, in one assertion: the rendered output is one
     * record, so every line after the first is a continuation the JDK generated. A
     * frame is TAB-indented and a cause line is the JVM's own {@code Caused by:} —
     * neither is something an attacker's text can become once its boundaries are
     * escaped.
     */
    private static void assertSingleRecord(String rendered, String what) {
        List<String> lines = linesOf(rendered);
        assertFalse(lines.isEmpty(), "nothing was rendered at all");
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            // An empty line carries nothing a reader could mistake for a record, and
            // the pattern's own trailing %n produces one.
            if (line.isEmpty() || line.startsWith("\t") || line.startsWith("Caused by: ")) {
                continue;
            }
            fail("a CR/LF in " + what + " forged a log record (CWE-117): line " + (i + 1)
                    + " is neither a stack frame nor a cause, so a reader takes it for a new, server-authored entry."
                    + "\n  offending line: " + line + "\n  whole output:\n" + rendered);
        }
    }

    private static String consoleFormat() throws Exception {
        String format = applicationProperties().getProperty("quarkus.log.console.format");
        assertNotNull(format, "the console pattern these tests grade against has to exist");
        return format;
    }

    /**
     * The MAIN application.properties, read off disk.
     * {@code src/test/resources/application.properties} shadows it on the classpath
     * and defines no console format, so the artefact these assertions protect has
     * to be opened directly.
     */
    private static Properties applicationProperties() throws Exception {
        var path = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);

        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        return properties;
    }
}
