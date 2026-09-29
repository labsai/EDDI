/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source guards for {@link ClientContextGuard}: its list only protects the
 * engine if every engine-written context key is on it, and every reader of one
 * looks it up exactly.
 */
@DisplayName("ClientContextGuard source guards")
class ClientContextGuardSourceTest {

    private static List<Path> javaSources(String root) throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of(root))) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /**
     * Every reader of a context key must look it up exactly. The step's
     * {@code getLatestData} and the stack's {@code getAllLatestData} match by
     * prefix, so a context-key read through either accepts a client-chosen
     * extension of the name ({@code groupIdSuffix} for {@code groupId}).
     * {@code context:*} keys are client-writable, which makes a prefix read of one
     * a bug even for a key that is not reserved today. Comment lines are skipped.
     */
    @Test
    @DisplayName("no main source reads a context key through a prefix-matching lookup")
    void noPrefixLookupOfAContextKey() throws IOException {
        Pattern prefixRead = Pattern.compile("get(?:All)?LatestData\\(([^)]*)\\)");
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources("src/main/java")) {
            List<String> lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                    continue;
                }
                Matcher matcher = prefixRead.matcher(line);
                while (matcher.find()) {
                    if (matcher.group(1).toLowerCase(Locale.ROOT).contains("context")) {
                        offenders.add(source + ":" + (i + 1) + ": " + line);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "use getData / getExactDataPerStep for context keys: " + offenders);
    }

    /**
     * The list is only as good as its coverage: a context key the engine writes
     * into a conversation it starts, but that is missing from
     * {@link ClientContextGuard#RESERVED_KEYS}, is a key a client can forge.
     * <p>
     * <b>What this scan does and does not cover.</b> It reads every source file
     * under the group orchestrator ({@code engine/internal/groups}) and the LLM
     * module ({@code modules/llm}, where the dynamic-agent tools live) and finds
     * context entries keyed by a string <em>literal</em> — {@code .put("key", new
     * Context(...))} and {@code Map.of("key", new Context(...))}, across line
     * breaks. A write keyed by a constant or a computed name is not detected, nor
     * are writers elsewhere in the engine.
     */
    @Test
    @DisplayName("every literal context key the group orchestrator or the LLM tools write is reserved")
    void everyEngineWrittenLiteralKeyIsReserved() throws IOException {
        Pattern write = Pattern.compile("(?:\\.put|Map\\.of)\\(\\s*\"([A-Za-z_]+)\",\\s*new Context\\(");
        Set<String> written = new TreeSet<>();
        for (String root : List.of("src/main/java/ai/labs/eddi/engine/internal/groups", "src/main/java/ai/labs/eddi/modules/llm")) {
            for (Path source : javaSources(root)) {
                Matcher matcher = write.matcher(Files.readString(source));
                while (matcher.find()) {
                    written.add(matcher.group(1));
                }
            }
        }
        assertTrue(written.containsAll(Set.of("groupId", "groupConversationId", "dynamicAgentConfig", "dynamicCreatedAgentIds")),
                "the scan no longer finds the orchestrator's known writes — the pattern has drifted from the source: " + written);

        Set<String> unreserved = new TreeSet<>(written);
        unreserved.removeAll(ClientContextGuard.RESERVED_KEYS);
        assertTrue(unreserved.isEmpty(), "context keys written by the engine but not reserved: " + unreserved);
    }
}
