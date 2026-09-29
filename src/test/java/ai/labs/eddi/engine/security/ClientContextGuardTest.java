/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClientContextGuard}: engine-reserved context keys never pass from a
 * client into the engine unless an operator explicitly permits them.
 */
@DisplayName("ClientContextGuard")
class ClientContextGuardTest {

    private static Context str(String value) {
        return new Context(Context.ContextType.string, value);
    }

    @ParameterizedTest(name = "reserved key {0} is removed")
    @ValueSource(strings = {"groupId", "groupConversationId", "groupDepth", "groupTranscript", "dynamicAgentConfig",
            "dynamicCreatedAgentIds", "delegationDepth"})
    void reservedKeyIsRemoved(String key) {
        Map<String, Context> context = new HashMap<>();
        context.put(key, str("x"));
        context.put("language", str("en"));

        var stripped = ClientContextGuard.strict().strip(context);

        assertFalse(stripped.containsKey(key));
        assertEquals("en", stripped.get("language").getValue(), "ordinary keys must pass through");
    }

    @Test
    @DisplayName("every reserved key is covered by the parameterized test above")
    void reservedSetIsExactlyTheDocumentedOnes() {
        assertEquals(7, ClientContextGuard.RESERVED_KEYS.size());
    }

    @Test
    @DisplayName("a context without reserved keys is returned untouched")
    void cleanContextIsReturnedAsIs() {
        Map<String, Context> context = new LinkedHashMap<>();
        context.put("language", str("en"));
        context.put("userInfo", str("u"));

        assertSame(context, ClientContextGuard.strict().strip(context));
    }

    @Test
    @DisplayName("null and empty maps are handled")
    void nullAndEmpty() {
        assertNull(ClientContextGuard.strict().strip((Map<String, Context>) null));
        Map<String, Context> empty = Map.of();
        assertSame(empty, ClientContextGuard.strict().strip(empty));
        assertNull(ClientContextGuard.strict().strip((InputData) null));
    }

    @Test
    @DisplayName("an immutable client map is copied, not mutated")
    void immutableMapIsCopied() {
        var context = Map.of("groupId", str("g"), "language", str("en"));

        var stripped = ClientContextGuard.strict().strip(context);

        assertEquals(Map.of("language", context.get("language")), stripped);
        assertTrue(context.containsKey("groupId"), "the argument itself must not be modified");
    }

    @Test
    @DisplayName("matching is case-sensitive, and a key that merely contains a reserved name is ordinary")
    void caseSensitiveAndAnchoredAtTheStart() {
        Map<String, Context> context = new HashMap<>();
        context.put("GroupId", str("a"));
        context.put("screenGroupId", str("b"));
        context.put("group", str("c"));

        assertSame(context, ClientContextGuard.strict().strip(context));
    }

    /**
     * Defence in depth for the prefix-matching step lookups (CWE-863, PR #831): a
     * key that starts with a reserved name is stored as {@code context:<key>},
     * which {@code getLatestData("context:<reserved>")} would also return.
     */
    @Test
    @DisplayName("a key that starts with a reserved name is removed too")
    void keysExtendingAReservedNameAreRemoved() {
        Map<String, Context> context = new HashMap<>();
        context.put("groupIdSuffix", str("another-teams-group"));
        context.put("groupId_display", str("x"));
        context.put("dynamicAgentConfigX", str("{}"));
        context.put("dynamicCreatedAgentIdsX", str("victim"));
        context.put("delegationDepthX", str("0"));
        context.put("groupConversationIdX", str("gc-other"));
        context.put("language", str("en"));

        var stripped = ClientContextGuard.strict().strip(context);

        assertEquals(Map.of("language", context.get("language")), stripped);
        assertTrue(ClientContextGuard.shadowsReserved("groupTranscriptV2"));
        assertFalse(ClientContextGuard.shadowsReserved("group"));
        assertFalse(ClientContextGuard.shadowsReserved(null));
    }

    @Test
    @DisplayName("a permitted reserved key permits its extensions; other reserved prefixes are still removed")
    void permittedKeyPermitsItsExtensions() {
        var guard = new ClientContextGuard(Optional.of(List.of("groupId")));
        Map<String, Context> context = new HashMap<>();
        context.put("groupIdLabel", str("g"));
        context.put("delegationDepthMax", str("0"));

        var stripped = guard.strip(context);

        assertTrue(stripped.containsKey("groupIdLabel"));
        assertFalse(stripped.containsKey("delegationDepthMax"));
    }

    @Test
    @DisplayName("InputData context is stripped in place")
    void inputDataIsStrippedInPlace() {
        Map<String, Context> context = new HashMap<>();
        context.put("dynamicAgentConfig", new Context(Context.ContextType.object, Map.of("enabled", true)));
        context.put("language", str("de"));
        var inputData = new InputData("hi", context);

        var returned = ClientContextGuard.strict().strip(inputData);

        assertSame(inputData, returned);
        assertFalse(inputData.getContext().containsKey("dynamicAgentConfig"));
        assertEquals("de", inputData.getContext().get("language").getValue());
        assertEquals("hi", inputData.getInput());
    }

    @Test
    @DisplayName("an operator-permitted reserved key passes; the others are still removed")
    void permittedKeyPasses() {
        var guard = new ClientContextGuard(Optional.of(List.of(" groupId ", "notAReservedKey")));
        Map<String, Context> context = new HashMap<>();
        context.put("groupId", str("g"));
        context.put("delegationDepth", str("0"));

        var stripped = guard.strip(context);

        assertTrue(stripped.containsKey("groupId"));
        assertFalse(stripped.containsKey("delegationDepth"));
    }
}
