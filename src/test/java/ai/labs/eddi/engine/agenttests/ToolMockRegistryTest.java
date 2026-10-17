/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Defaults;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.MockResponse;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolMock;
import ai.labs.eddi.engine.agenttests.ToolMockRegistry.Outcome;
import ai.labs.eddi.engine.memory.IConversationMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ToolMockRegistry")
class ToolMockRegistryTest {

    private static final String USER = AgentTestConversations.userIdFor("0123456789abcdef01234567", 0, 1);

    private static final List<ToolMock> MOCKS = List.of(
            new ToolMock("getAvailability", Map.of("city", "Vienna"), new MockResponse(200, Map.of("rooms", 3))),
            new ToolMock("createBooking", null, new MockResponse(403, "forbidden")),
            new ToolMock("lookup", null, new MockResponse(null, "plain text")));

    @AfterEach
    void tearDown() {
        ToolMockRegistry.unregister(USER);
    }

    private static IConversationMemory memoryOf(String userId) {
        var memory = mock(IConversationMemory.class);
        when(memory.getUserId()).thenReturn(userId);
        return memory;
    }

    private static ToolMockRegistry.Session register(Defaults defaults) {
        var session = new ToolMockRegistry.Session(new AgentTestSuite("s", null, defaults, null, MOCKS, null));
        ToolMockRegistry.register(USER, session);
        return session;
    }

    @Test
    @DisplayName("an ordinary conversation is never touched")
    void ordinaryConversationPassesThrough() {
        register(null);
        var memory = memoryOf("alice");

        assertNull(ToolMockRegistry.interceptToolCall(memory, "createBooking", "{}", "http"));
        assertNull(ToolMockRegistry.interceptRuleCall(memory, "createBooking", null, "httpcall"));
        assertTrue(ToolMockRegistry.mayRunLive(memory));
    }

    @Test
    @DisplayName("an http tool's mock reaches the model in the httpcall result shape")
    void httpMockIsShapedLikeAnHttpcallResult() {
        var session = register(null);

        String result = ToolMockRegistry.interceptToolCall(memoryOf(USER), "getAvailability", "{\"city\":\"Vienna\",\"nights\":2}", "http");

        assertEquals("{\"httpCode\":200,\"body\":{\"rooms\":3}}", result);
        assertEquals(Outcome.MOCKED, session.calls().getFirst().outcome());
    }

    @Test
    void otherSourcesGetTheBody() {
        register(null);

        assertEquals("plain text", ToolMockRegistry.interceptToolCall(memoryOf(USER), "lookup", "{}", "mcp"));
    }

    @Test
    @DisplayName("an unmocked call is refused, recorded, and never executed — fail closed by default")
    void unmockedCallFailsClosed() {
        var session = register(null);

        String result = ToolMockRegistry.interceptToolCall(memoryOf(USER), "getAvailability", "{\"city\":\"Graz\"}", "http");

        assertEquals(ToolMockRegistry.NOT_EXECUTED_JSON, result, "argsSubset did not match, so there is no mock");
        assertEquals(Outcome.NOT_EXECUTED, session.calls().getFirst().outcome());
        assertFalse(ToolMockRegistry.mayRunLive(memoryOf(USER)));
    }

    @Test
    @DisplayName("a test conversation the registry does not know is refused, not executed")
    void unknownTestConversationFailsClosed() {
        // Not "no mock defined": the session is missing, which is a different diagnosis
        // (run over, or the turn ran on another cluster node).
        assertEquals(ToolMockRegistry.UNKNOWN_SESSION_JSON, ToolMockRegistry.interceptToolCall(memoryOf(USER), "lookup", "{}", "mcp"));
        var rule = ToolMockRegistry.interceptRuleCall(memoryOf(USER), "lookup", null, "httpcall");
        assertNotNull(rule);
        assertEquals(ToolMockRegistry.NOT_EXECUTED_HTTP_CODE, rule.httpCode());
        assertEquals(ToolMockRegistry.UNKNOWN_SESSION_JSON, rule.bodyAsText());
        assertFalse(ToolMockRegistry.mayRunLive(memoryOf(USER)));
    }

    @Test
    void unmockedLiveIsAnExplicitOptIn() {
        var session = register(new Defaults(null, AgentTestSuite.UNMOCKED_LIVE, null, null, null));

        assertNull(ToolMockRegistry.interceptToolCall(memoryOf(USER), "somethingElse", "{}", "http"));
        assertEquals("plain text", ToolMockRegistry.interceptToolCall(memoryOf(USER), "lookup", "{}", "mcp"), "mocks still win");
        assertEquals(List.of(Outcome.LIVE, Outcome.MOCKED), session.calls().stream().map(ToolMockRegistry.RecordedCall::outcome).toList());
    }

    @Test
    void toolModeLiveIgnoresMocks() {
        register(new Defaults(AgentTestSuite.TOOL_MODE_LIVE, null, null, null, null));

        assertNull(ToolMockRegistry.interceptToolCall(memoryOf(USER), "lookup", "{}", "mcp"));
    }

    @Test
    @DisplayName("memory-writing tools never run live in a test, whatever the suite says")
    void memoryToolsAreNeverLive() {
        var session = register(new Defaults(AgentTestSuite.TOOL_MODE_LIVE, null, null, null, null));

        String result = ToolMockRegistry.interceptToolCall(memoryOf(USER), "rememberFact", "{}", "memory");

        assertEquals(ToolMockRegistry.MEMORY_BLOCKED_JSON, result);
        assertEquals(Outcome.BLOCKED, session.calls().getFirst().outcome());
    }

    @Test
    @DisplayName("rule-triggered calls have no arguments, so only mocks without argsSubset match them")
    void ruleCallsMatchOnNameOnly() {
        register(null);

        var mocked = ToolMockRegistry.interceptRuleCall(memoryOf(USER), "createBooking", null, "httpcall");
        assertEquals(403, mocked.httpCode());
        assertEquals("forbidden", mocked.bodyAsText());

        var unmatched = ToolMockRegistry.interceptRuleCall(memoryOf(USER), "getAvailability", null, "httpcall");
        assertEquals(Outcome.NOT_EXECUTED, unmatched.outcome());
    }

    @Test
    void lazyDiscoveryIsNotAToolCall() {
        register(null);

        assertNull(ToolMockRegistry.interceptToolCall(memoryOf(USER), "discover_tools", "{}", "builtin"));
    }

    @Test
    void callsAreAttributedToTheirTurn() {
        var session = register(null);
        ToolMockRegistry.interceptToolCall(memoryOf(USER), "lookup", "{}", "mcp");
        session.startTurn(2);
        ToolMockRegistry.interceptToolCall(memoryOf(USER), "createBooking", "{}", "http");

        assertEquals(List.of("lookup"), session.callsOfTurn(0).stream().map(ToolMockRegistry.RecordedCall::tool).toList());
        assertEquals(List.of("createBooking"), session.callsOfTurn(2).stream().map(ToolMockRegistry.RecordedCall::tool).toList());
    }
}
