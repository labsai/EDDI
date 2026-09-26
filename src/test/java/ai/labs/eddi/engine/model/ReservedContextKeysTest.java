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
     * The list is only as good as its coverage: a context key the group
     * orchestrator writes for a member turn, but that is missing here, is a key a
     * client can forge. Scans the two writers for literal {@code put("key", new
     * Context(...))} calls and requires each key (bar the per-attachment ones,
     * which are client-facing by design) to be reserved.
     */
    @Test
    @DisplayName("every context key the group orchestrator writes is reserved")
    void everyOrchestratorWrittenKeyIsReserved() throws IOException {
        Pattern put = Pattern.compile("\\.put\\(\"([A-Za-z_]+)\",\\s*new Context\\(");
        Set<String> written = new TreeSet<>();
        for (String source : List.of("src/main/java/ai/labs/eddi/engine/internal/groups/MemberTurnExecutor.java",
                "src/main/java/ai/labs/eddi/engine/internal/groups/GroupLifecycleOps.java")) {
            Matcher matcher = put.matcher(Files.readString(Path.of(source)));
            while (matcher.find()) {
                written.add(matcher.group(1));
            }
        }
        assertFalse(written.isEmpty(), "the scan found no writes — the pattern no longer matches the source");

        Set<String> unreserved = new TreeSet<>(written);
        unreserved.removeAll(ReservedContextKeys.ALL);
        assertTrue(unreserved.isEmpty(), "context keys written by the group orchestrator but not reserved: " + unreserved);
    }
}
