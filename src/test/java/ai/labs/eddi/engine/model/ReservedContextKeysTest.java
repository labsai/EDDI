/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservedContextKeysTest {

    private static Context text(String value) {
        return new Context(Context.ContextType.string, value);
    }

    @Test
    @DisplayName("reserved keys are dropped, everything else is kept")
    void stripsOnlyReservedKeys() {
        Map<String, Context> input = new HashMap<>();
        for (String key : ReservedContextKeys.ALL) {
            input.put(key, text("forged"));
        }
        input.put("lang", text("en"));
        input.put("properties", text("property(x(y))"));

        Map<String, Context> stripped = ReservedContextKeys.stripFromExternal(input, "test");

        assertEquals(Set.of("lang", "properties"), stripped.keySet());
        assertEquals(ReservedContextKeys.ALL.size() + 2, input.size(), "the caller's map must not be modified");
    }

    @Test
    @DisplayName("a map with nothing reserved is returned as-is, so an immutable one stays usable")
    void cleanMapIsReturnedUnchanged() {
        Map<String, Context> clean = Map.of("slack", text("true"));

        assertSame(clean, ReservedContextKeys.stripFromExternal(clean, "test"));
    }

    @Test
    @DisplayName("an immutable map with a reserved key is copied, not mutated")
    void immutableMapIsCopied() {
        Map<String, Context> immutable = Map.of("groupId", text("g"), "lang", text("de"));

        Map<String, Context> stripped = ReservedContextKeys.stripFromExternal(immutable, "test");

        assertEquals(Set.of("lang"), stripped.keySet());
    }

    @Test
    @DisplayName("null and empty pass through")
    void nullAndEmpty() {
        assertNull(ReservedContextKeys.stripFromExternal((Map<String, Context>) null, "test"));
        assertNull(ReservedContextKeys.stripFromExternal((InputData) null, "test"));
        Map<String, Context> empty = Map.of();
        assertSame(empty, ReservedContextKeys.stripFromExternal(empty, "test"));
    }

    @Test
    @DisplayName("InputData gets a filtered map; one with nothing reserved keeps its own")
    void inputDataIsFilteredInPlace() {
        var forged = new InputData("hi", Map.of("dynamicCreatedAgentIds", text("victim"), "lang", text("en")));
        ReservedContextKeys.stripFromExternal(forged, "test");
        assertEquals(Set.of("lang"), forged.getContext().keySet());

        Map<String, Context> own = Map.of("lang", text("en"));
        var clean = new InputData("hi", own);
        ReservedContextKeys.stripFromExternal(clean, "test");
        assertSame(own, clean.getContext());
    }

    @Test
    void isReserved() {
        assertTrue(ReservedContextKeys.isReserved("dynamicAgentConfig"));
        assertFalse(ReservedContextKeys.isReserved("lang"));
        assertFalse(ReservedContextKeys.isReserved(null));
    }

    /**
     * The list is only as good as its coverage: a context key the engine writes
     * into a conversation it starts, but that is missing here, is a key a client
     * can forge.
     * <p>
     * <b>What this scan does and does not cover.</b> It reads every source file
     * under the group orchestrator ({@code engine/internal/groups}) and the LLM
     * module ({@code modules/llm}, where the dynamic-agent tools live) and finds
     * context entries keyed by a string <em>literal</em> — {@code .put("key", new
     * Context(...))} and {@code Map.of("key", new Context(...))}, across line
     * breaks. A write keyed by a {@code ReservedContextKeys} constant is reserved
     * by construction and needs no scan; a write keyed by any other constant or
     * computed name (the per-attachment {@code attachment_N} keys, which are
     * client-facing by design) is not detected. Writers elsewhere in the engine are
     * not scanned either.
     */
    @Test
    @DisplayName("every literal context key the group orchestrator or the LLM tools write is reserved")
    void everyEngineWrittenLiteralKeyIsReserved() throws IOException {
        Pattern write = Pattern.compile("(?:\\.put|Map\\.of)\\(\\s*\"([A-Za-z_]+)\",\\s*new Context\\(");
        Set<String> written = new TreeSet<>();
        for (String root : List.of("src/main/java/ai/labs/eddi/engine/internal/groups", "src/main/java/ai/labs/eddi/modules/llm")) {
            List<Path> sources;
            try (Stream<Path> walk = Files.walk(Path.of(root))) {
                sources = walk.filter(path -> path.toString().endsWith(".java")).toList();
            }
            for (Path source : sources) {
                Matcher matcher = write.matcher(Files.readString(source));
                while (matcher.find()) {
                    written.add(matcher.group(1));
                }
            }
        }
        assertTrue(written.containsAll(Set.of("groupId", "groupConversationId", "dynamicAgentConfig", "dynamicCreatedAgentIds")),
                "the scan no longer finds the orchestrator's known writes — the pattern has drifted from the source: " + written);

        Set<String> unreserved = new TreeSet<>(written);
        unreserved.removeAll(ReservedContextKeys.ALL);
        assertTrue(unreserved.isEmpty(), "context keys written by the engine but not reserved: " + unreserved);
    }
}
