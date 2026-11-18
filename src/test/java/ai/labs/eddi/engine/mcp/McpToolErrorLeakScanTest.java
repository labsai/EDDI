/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No MCP tool may echo an exception's text to its caller: a datastore or driver
 * message would reach an {@code eddi-viewer}. A tool describes a failure
 * through {@link McpToolUtils#toolFailure} (a stable message and a reference
 * id; the exception goes to the log) or, where the type is one EDDI raises
 * itself for the caller, through {@link McpToolUtils#safeMessage}, which
 * repeats only the message of a type {@link McpToolUtils#isCallerFacing} lists.
 * <p>
 * This scan keeps the raw forms out of the {@code Mcp*Tools} classes: inside
 * the arguments of an {@code errorJson(} call, or of a
 * {@code put("…error…", …)} that builds a result, it flags
 * {@code getMessage()}, {@code getLocalizedMessage()}, {@code toString()},
 * {@code String.valueOf(} and {@code describe(}, and it flags the two-argument
 * {@code errorJson(prefix, exception)} that used to bypass the filter. A site
 * that is legitimate is named in {@link #ALLOWED} with the reason.
 * <p>
 * What it does <b>not</b> see: an exception's text reaching a result by another
 * route (a variable assigned from it, a helper, a log-then-return of a service
 * result). It is a tripwire for the known pattern, not proof.
 */
@DisplayName("MCP tools — no exception text in error results")
class McpToolErrorLeakScanTest {

    private static final Path MCP = Path.of("src", "main", "java", "ai", "labs", "eddi", "engine", "mcp");

    private static final Pattern RAW_TEXT = Pattern
            .compile("getMessage\\(\\)|getLocalizedMessage\\(\\)|\\.toString\\(\\)|String\\.valueOf\\(|\\bdescribe\\(");

    /**
     * Calls that may carry such text, each whitespace-collapsed, with the reason.
     * Empty today: every site goes through {@code toolFailure} or
     * {@code safeMessage}. An entry needs a comment saying why the text is authored
     * for the caller.
     */
    private static final Map<String, String> ALLOWED = Map.of();

    /**
     * The whole text of every call that starts with one of {@code starts}: from the
     * start token to its closing parenthesis, strings skipped.
     */
    static List<String> calls(String source, String... starts) {
        List<String> found = new ArrayList<>();
        for (String start : starts) {
            int from = 0;
            while (true) {
                int at = source.indexOf(start, from);
                if (at < 0) {
                    break;
                }
                from = at + 1;
                if (start.startsWith("errorJson") && at > 0 && Character.isJavaIdentifierPart(source.charAt(at - 1))) {
                    continue; // somethingErrorJson(
                }
                int end = closing(source, at + start.indexOf('('));
                if (end > 0) {
                    found.add(source.substring(at, end + 1).replaceAll("\\s+", " "));
                }
            }
        }
        return found;
    }

    /** The index of the parenthesis that closes the one at {@code open}, or -1. */
    private static int closing(String source, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The number of top-level arguments of a call text. */
    static int argumentCount(String call) {
        int open = call.indexOf('(');
        int depth = 0;
        int commas = 0;
        boolean inString = false;
        for (int i = open; i < call.length(); i++) {
            char c = call.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 1) {
                commas++;
            }
        }
        return commas + 1;
    }

    /**
     * Calls that carry exception text into an error result, per the rules above.
     */
    static List<String> leaks(String source) {
        List<String> leaks = new ArrayList<>();
        for (String call : calls(source, "errorJson(")) {
            if (argumentCount(call) == 2 || RAW_TEXT.matcher(call).find()) {
                leaks.add(call);
            }
        }
        // result builders: put("error", …), put("deployError", …), put("lastError", …)
        for (String call : calls(source, ".put(")) {
            if (call.matches("\\.put\\(\"[A-Za-z]*[eE]rror[A-Za-z]*\".*") && RAW_TEXT.matcher(call).find()) {
                leaks.add(call);
            }
        }
        return leaks;
    }

    @Test
    @DisplayName("the scan recognises the raw forms and lets the vetted ones through")
    void scanRecognisesTheForms() {
        assertEquals(1, leaks("return errorJson(\"x\", e);").size());
        assertEquals(1, leaks("return errorJson(e.getMessage());").size());
        assertEquals(1, leaks("return errorJson(\"Invalid decision: \" + e.getMessage(), \"BAD_REQUEST\", null);").size());
        assertEquals(1, leaks("return errorJson(String.valueOf(response.getEntity()));").size());
        assertEquals(1, leaks("return errorJson(\"x: \" + describe(e));").size());
        assertEquals(1, leaks("return errorJson(e.toString());").size());
        assertEquals(1, leaks("result.put(\"deployError\", \"Redeployment failed: \" + deployError.getMessage());").size());
        assertEquals(1, leaks("body.put(\"error\", e.getLocalizedMessage());").size());
        assertTrue(leaks("return errorJson(\"a, b\");").isEmpty());
        assertTrue(leaks("return errorJson(msg(a, b), \"CODE\", null);").isEmpty());
        assertTrue(leaks("return errorJson(safeMessage(e, \"refused\"));").isEmpty());
        assertTrue(leaks("result.put(\"nextFire\", next.toString());").isEmpty());
        assertTrue(leaks("LOGGER.warn(\"x\" + e.getMessage());").isEmpty());
    }

    @Test
    @DisplayName("no Mcp*Tools class puts exception text into an error result, and McpToolUtils declares no prefix-and-exception errorJson")
    void noExceptionTextInErrorResults() throws IOException {
        assertTrue(Files.isDirectory(MCP), "run from the project root");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(MCP)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (name.equals("McpToolUtils.java")) {
                    if (source.contains("static String errorJson(String prefix, Throwable")) {
                        offenders.add(name + ": declares errorJson(String, Throwable)");
                    }
                    continue;
                }
                if (!name.matches("Mcp\\w*Tools\\.java")) {
                    continue;
                }
                for (String leak : leaks(source)) {
                    if (!ALLOWED.containsKey(leak)) {
                        offenders.add(name + ": " + leak);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Use McpToolUtils.toolFailure(LOGGER, tool, what, e) — it logs the exception under a reference id and "
                + "answers a stable message — or safeMessage(e, fallback) for a type isCallerFacing lists. Offenders: " + offenders);
    }

    @Test
    @DisplayName("every allow-list entry still matches a call (no stale entries)")
    void allowListIsNotStale() throws IOException {
        List<String> all = new ArrayList<>();
        try (Stream<Path> files = Files.list(MCP)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("Mcp\\w*Tools\\.java")).toList()) {
                all.addAll(leaks(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        assertTrue(all.containsAll(ALLOWED.keySet()), "stale allow-list entries: " + ALLOWED.keySet());
    }
}
