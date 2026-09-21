package ai.labs.eddi.utils;

/**
 * Sanitizes user-controlled values before logging to prevent log injection
 * (CWE-117).
 *
 * <p>
 * EDDI applies two different rules, and the difference is deliberate:
 * </p>
 * <ul>
 * <li>{@link #sanitize(String)} is the <em>call-site</em> rule. A caller that
 * knows it is about to log one attacker-reachable value — an id, a name, a URI
 * — collapses every boundary character in it to {@code _}. It is lossy on
 * purpose: the values it is pointed at are short and have no legitimate
 * newlines, and {@code _} reads better in a log line than an escape
 * sequence.</li>
 * <li>{@link #escapeRecordBoundaries(String)} is the <em>record-level</em>
 * rule, applied once to a whole log record on its way out. It cannot be lossy:
 * it is pointed at text nobody vetted — including a library exception's message
 * and, in the two places EDDI does it, a deliberately multi-line message — so
 * it escapes boundaries reversibly instead of destroying them.</li>
 * </ul>
 *
 * <p>
 * The record-level rule exists because the call-site one cannot reach far
 * enough. {@code quarkus.log.console.format} ends in {@code %s%e}, and
 * {@code %e} renders a stack trace whose first line is the throwable's own
 * {@code toString()} — {@code ClassName: message}. Over 400 log calls in
 * {@code src/main/java} pass a throwable, so an attacker-controlled CR/LF
 * inside an exception message reached the console verbatim no matter how
 * carefully the message half was sanitized. Pointing {@link #sanitize} at that
 * half is not an option either: it would flatten the rendered stack trace into
 * one unreadable line.
 * </p>
 *
 * @author ginccc
 * @since 6.0.2
 * @see ai.labs.eddi.engine.runtime.LogCaptureFilter the record-level rule's one
 *      application point
 */
public final class LogSanitizer {

    /**
     * Unicode LINE SEPARATOR. Written as a numeric constant rather than a
     * {@code '\\u2028'} char literal because the project formatter decodes such an
     * escape back into the raw character, and a raw U+2028 sitting in a .java file
     * is a thing editors, diffs and reviewers mangle.
     */
    private static final char LINE_SEPARATOR = 0x2028;

    /** Unicode PARAGRAPH SEPARATOR; see {@link #LINE_SEPARATOR}. */
    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    private LogSanitizer() {
    }

    /**
     * Remove newlines and control characters from a value before logging. Newlines
     * and tabs are replaced with underscores (preserving readability); other
     * control characters are stripped entirely.
     *
     * @param value
     *            the value to sanitize (may be null)
     * @return sanitized string safe for log output, or {@code "null"} if input is
     *         null
     */
    public static String sanitize(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sanitized.append('_');
            } else if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
                // Control characters stripped; Unicode line/paragraph separators blocked
            } else {
                sanitized.append(c);
            }
        }
        return sanitized.toString();
    }

    /**
     * Neutralize every character that could end a log record, escaping it rather
     * than removing it.
     *
     * <p>
     * A log record is one line. Anything that can end a line can end a record, and
     * whatever follows it is read by a log viewer, a shipper or a SIEM rule as a
     * new, server-authored entry. This turns each such character into a
     * two-character escape — {@code \n}, {@code \r} — or a {@code \\uXXXX} escape,
     * so the text still says what it said and still occupies exactly one line.
     * </p>
     *
     * <h3>What is escaped, and what is not</h3>
     * <ul>
     * <li><b>CR and LF</b> — the record boundary itself.</li>
     * <li><b>U+2028 and U+2029</b> — the Unicode line and paragraph separators.
     * They are not line breaks to a JVM, but they are to JavaScript, to JSON
     * consumers and to several log viewers, so a forged record can be smuggled
     * through them into exactly the tools an operator reads logs in.</li>
     * <li><b>Every other ISO control character except TAB</b> — ESC in particular,
     * which carries terminal escape sequences into any console a log is tailed
     * in.</li>
     * <li><b>TAB is kept verbatim.</b> It cannot end a record, and it is what
     * indents {@code \tat …} and {@code \t... N more} in a rendered stack trace.
     * Escaping it would cost the trace its shape for no security gain.</li>
     * <li><b>A backslash is NOT escaped.</b> The escaping is therefore not
     * injective: a message that literally contained the two characters {@code \n}
     * reads the same as one that contained a newline. That is a cosmetic ambiguity
     * and not a forgery — neither breaks the line — and the alternative doubles
     * every backslash in the Windows paths and regexes that exception messages are
     * full of.</li>
     * </ul>
     *
     * <h3>Why this is applied to the throwable's message, not to rendered
     * output</h3>
     * <p>
     * The obvious reading of "sanitize the rendered {@code %s%e}" is to scan the
     * finished stack trace and escape the line breaks that do not begin a genuine
     * continuation line ({@code \tat }, {@code Caused by:}, {@code \t... N more}).
     * EDDI does not do that, because it is a guess about text after the fact: those
     * three prefixes are also three strings an attacker can put in an exception
     * message, so the scan has to decide which {@code Caused by:} is the JVM's and
     * which is the payload, and it has no way to know.
     * </p>
     * <p>
     * There is no need to guess. In a rendered trace the only text an attacker
     * reaches is the {@code toString()} of each throwable in the graph — its type
     * name, which it does not control, and its message, which it does. Every other
     * line is generated by the JDK from the {@code StackTraceElement} array. So
     * EDDI escapes the messages <em>before</em> the trace is rendered, by
     * substituting a copy of the throwable, and lets the JDK produce the structure
     * from clean input. Nothing is parsed, nothing is guessed, and the frames come
     * out byte-for-byte as they always did.
     * </p>
     *
     * @param value
     *            the text to escape (may be null)
     * @return the escaped text; the same instance when nothing needed escaping, and
     *         {@code null} for a {@code null} input — unlike {@link #sanitize},
     *         which renders null as the string {@code "null"}. A throwable's
     *         message is legitimately null, and turning that into {@code "null"}
     *         would change its printed line from {@code java.io.IOException} to
     *         {@code java.io.IOException: null}
     */
    public static String escapeRecordBoundaries(String value) {
        if (value == null || !needsEscaping(value)) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append(c);
                default -> {
                    if (isRecordBoundary(c)) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /**
     * Whether {@link #escapeRecordBoundaries} would change this text. Checked first
     * so the overwhelming majority of log records — which carry nothing to escape —
     * are handed back unchanged, and the caller can tell by identity that it has no
     * record to rewrite.
     */
    private static boolean needsEscaping(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (isRecordBoundary(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Whether this character can end, or be read as ending, a log record. */
    private static boolean isRecordBoundary(char c) {
        return c != '\t' && (Character.isISOControl(c) || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR);
    }
}
