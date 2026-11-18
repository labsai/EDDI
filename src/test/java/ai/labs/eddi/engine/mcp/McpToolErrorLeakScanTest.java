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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No MCP tool may echo an exception's text to its caller: a datastore or driver
 * message would reach an {@code eddi-viewer}. The one path that filters is
 * {@link McpToolUtils#toolFailure} (and {@code internalFailure}); the
 * two-argument {@code errorJson(prefix, exception)} that bypassed it is gone,
 * and this scan keeps a new call site, or a re-added overload, from bringing it
 * back.
 */
@DisplayName("MCP tools — no exception text in error results")
class McpToolErrorLeakScanTest {

    private static final Path MCP = Path.of("src", "main", "java", "ai", "labs", "eddi", "engine", "mcp");

    /**
     * The start offsets of every {@code errorJson(} call that has exactly two
     * arguments.
     */
    static List<Integer> twoArgumentCalls(String source) {
        List<Integer> hits = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = source.indexOf("errorJson(", from);
            if (at < 0) {
                return hits;
            }
            from = at + 1;
            if (at > 0 && Character.isJavaIdentifierPart(source.charAt(at - 1))) {
                continue; // somethingErrorJson(
            }
            int depth = 0;
            int commas = 0;
            boolean inString = false;
            for (int i = at + "errorJson".length(); i < source.length(); i++) {
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
                } else if (c == '(' || c == '{' || c == '[') {
                    depth++;
                } else if (c == ')' || c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                } else if (c == ',' && depth == 1) {
                    commas++;
                }
            }
            if (commas == 1) {
                hits.add(at);
            }
        }
    }

    @Test
    @DisplayName("the scan itself tells a two-argument errorJson from the one- and three-argument forms")
    void scanRecognisesTheOverload() {
        assertTrue(twoArgumentCalls("return errorJson(\"x\", e);").size() == 1);
        assertTrue(twoArgumentCalls("return errorJson(\"a, b\");").isEmpty());
        assertTrue(twoArgumentCalls("return errorJson(msg(a, b), \"CODE\", null);").isEmpty());
        assertTrue(twoArgumentCalls("return errorJson(\"x: \" + f(a, b), e);").size() == 1);
    }

    @Test
    @DisplayName("no MCP class calls errorJson with an exception, and McpToolUtils declares no such overload")
    void noTwoArgumentErrorJson() throws IOException {
        assertTrue(Files.isDirectory(MCP), "run from the project root");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MCP)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (int at : twoArgumentCalls(source)) {
                    int line = (int) source.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(file.getFileName() + ":" + line);
                }
                if (source.contains("static String errorJson(String prefix, Throwable")) {
                    offenders.add(file.getFileName() + ": declares errorJson(String, Throwable)");
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Use McpToolUtils.toolFailure(LOGGER, tool, what, e) — it logs the exception under a reference id and "
                + "answers a stable message. Offenders: " + offenders);
    }
}
