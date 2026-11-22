/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ClearedToolCalls;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The email approval gate (email plan v3 §8.1): its own gate after the agent's,
 * armed only for a tainted turn whose step carries a target's policy.
 */
class EmailApprovalGateTest {

    private static final Map<String, String> SOURCES = Map.of("createOrder", "http", "listOrders", "http", "calculate", "builtin");
    private static final Map<String, String> ENDPOINTS = Map.of("createOrder", "post:/orders", "listOrders", "get:/orders");

    static IConversationMemory memory(String origin, Context gate) {
        IConversationMemory memory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep step = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getOrigin()).thenReturn(origin);
        when(memory.getCurrentStep()).thenReturn(step);
        if (gate != null) {
            IData<Object> data = new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, gate);
            when(step.getData("context:" + EmailApprovalGate.CONTEXT_KEY)).thenReturn(data);
        }
        return memory;
    }

    private static Context actions(String... readOnly) {
        return EmailApprovalGate.contextValue("actions", List.of(readOnly), false, null, null);
    }

    private static ToolExecutionRequest call(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    @Nested
    @DisplayName("arming")
    class Arming {

        @Test
        @DisplayName("a tainted turn carrying a policy is gated")
        void taintedWithPolicy() {
            assertNotNull(EmailApprovalGate.of(memory(TurnOrigin.EMAIL, actions())));
        }

        @Test
        @DisplayName("an untainted turn is unaffected, even with the context key on its step")
        void untainted() {
            assertNull(EmailApprovalGate.of(memory(null, actions())));
        }

        @Test
        @DisplayName("a tainted turn without a target policy (a delegated conversation) has no email gate")
        void taintedWithoutPolicy() {
            assertNull(EmailApprovalGate.of(memory(TurnOrigin.EMAIL, null)));
        }

        @Test
        @DisplayName("policy none is written explicitly, and reads as no gate")
        void noneIsExplicit() {
            Context none = EmailApprovalGate.contextValue("none", List.of("*"), true, "x", "y");
            assertEquals(Map.of("policy", "none"), none.getValue());
            assertEquals(Map.of("policy", "none"), EmailApprovalGate.contextValue(null, List.of(), false, null, null).getValue());
            assertNull(EmailApprovalGate.of(memory(TurnOrigin.EMAIL, none)));
        }

        @Test
        @DisplayName("a gate on the step that cannot be read fails closed: every call gated, nothing read-only")
        void unreadableFailsClosed() {
            for (Context bad : List.of(new Context(Context.ContextType.string, "everyTurn"),
                    new Context(Context.ContextType.object, Map.of("readOnlyTools", List.of("*"))),
                    new Context(Context.ContextType.object, Map.of("policy", "sometimes", "readOnlyTools", List.of("*"))),
                    new Context(Context.ContextType.object, Map.of("policy", "actions", "readOnlyTools", "*")))) {
                var gate = EmailApprovalGate.of(memory(TurnOrigin.EMAIL, bad));
                assertNotNull(gate, "fails closed for " + bad.getValue());
                assertEquals("actions", gate.policy());
                assertTrue(gate.readOnlyTools().isEmpty());
                assertTrue(EmailApprovalGate.refusesRuleCall(memory(TurnOrigin.EMAIL, bad), List.of("lookup")));
            }
        }

        @Test
        @DisplayName("the stored map form (after a persistence round trip) parses the same")
        void storedMapForm() {
            var parsed = EmailApprovalGate.parse(Map.of("type", "object", "value",
                    Map.of("policy", "everyTurn", "readOnlyTools", List.of("listOrders"), "preRun", true, "rejectedReply", "No.")));
            assertNotNull(parsed);
            assertTrue(parsed.preRun());
            assertEquals("No.", parsed.rejectedReply());
            assertEquals(List.of("listOrders"), parsed.readOnlyTools());
        }
    }

    @Nested
    @DisplayName("tool calls")
    class ToolCalls {

        @Test
        @DisplayName("http.post pauses even though the agent exempts it; a read-only call runs")
        void exemptPostIsGated() {
            var agent = new ToolApprovalsConfig();
            agent.setRequireApproval(List.of("http:*"));
            agent.setExempt(List.of("createOrder", "listOrders"));
            var batch = List.of(call("c1", "createOrder"), call("c2", "listOrders"));
            var agentSplit = new ToolApprovalGate().classify(batch, SOURCES, ENDPOINTS, agent, ClearedToolCalls.none());
            assertTrue(agentSplit.gated().isEmpty(), "precondition: the agent's exempt lets both through");

            var gate = EmailApprovalGate.of(memory(TurnOrigin.EMAIL, actions("http.get:*")));
            var split = EmailApprovalGate.overlay(gate, agentSplit, SOURCES, ENDPOINTS);

            assertEquals(List.of("c1"), split.gated().stream().map(ToolExecutionRequest::id).toList());
            assertEquals(List.of("c2"), split.allowed().stream().map(ToolExecutionRequest::id).toList());
            assertEquals(EmailApprovalGate.GATE_REASON, split.gateReasonByCallId().get("c1"));
            assertTrue(EmailApprovalGate.gatedByEmail(split.gateReasonByCallId()));
        }

        @Test
        @DisplayName("readOnlyTools never un-gates a call the agent gates")
        void readOnlyDoesNotUngate() {
            var agent = new ToolApprovalsConfig();
            agent.setRequireApproval(List.of("listOrders"));
            var agentSplit = new ToolApprovalGate().classify(List.of(call("c1", "listOrders")), SOURCES, ENDPOINTS, agent,
                    ClearedToolCalls.none());
            var gate = EmailApprovalGate.of(memory(TurnOrigin.EMAIL, actions("listOrders")));

            var split = EmailApprovalGate.overlay(gate, agentSplit, SOURCES, ENDPOINTS);

            assertEquals(1, split.gated().size());
            assertEquals("listOrders", split.gateReasonByCallId().get("c1"), "the agent's own reason stays");
        }

        @Test
        @DisplayName("at most once: a reissue of an approved, executed call is gated again, also when the agent gates nothing")
        void reissueIsGatedAgain() {
            var cleared = ClearedToolCalls.none();
            cleared.clear("c1", "createOrder", "{}");
            var agent = new ToolApprovalsConfig();
            var agentSplit = new ToolApprovalGate().classify(List.of(call("c1", "createOrder")), SOURCES, ENDPOINTS, agent, cleared);
            assertEquals(1, agentSplit.allowed().size(), "precondition: an inert agent gate lets the reissue through");

            var split = EmailApprovalGate.overlay(EmailApprovalGate.of(memory(TurnOrigin.EMAIL, actions())), agentSplit, SOURCES, ENDPOINTS);

            assertEquals(1, split.gated().size(), "the email gate puts the repeat back in front of a human");
            assertTrue(split.allowed().isEmpty());
        }

        @Test
        @DisplayName("at most once: with an active agent gate the repeat keeps the agent gate's repeat reason")
        void reissueKeepsRepeatReason() {
            var cleared = ClearedToolCalls.none();
            cleared.clear("c1", "createOrder", "{}");
            var agent = new ToolApprovalsConfig();
            agent.setRequireApproval(List.of("other"));
            var agentSplit = new ToolApprovalGate().classify(List.of(call("c1", "createOrder")), SOURCES, ENDPOINTS, agent, cleared);

            var split = EmailApprovalGate.overlay(EmailApprovalGate.of(memory(TurnOrigin.EMAIL, actions("createOrder"))), agentSplit, SOURCES,
                    ENDPOINTS);

            assertEquals(ToolApprovalGate.REPEAT_REASON, split.gateReasonByCallId().get("c1"),
                    "read-only or not, a repeat of an executed approval is never let through");
        }

        @Test
        @DisplayName("EDDI's built-in loop helpers are not gated; an MCP tool of the same name is")
        void loopControlTools() {
            var gate = EmailApprovalGate.of(memory(TurnOrigin.EMAIL, actions()));
            var builtin = new ToolApprovalGate.GateResult(List.of(), List.of(call("c1", "discover_tools")), Map.of());
            assertSame(builtin, EmailApprovalGate.overlay(gate, builtin, Map.of("discover_tools", "builtin"), Map.of()));
            var mcp = new ToolApprovalGate.GateResult(List.of(), List.of(call("c1", "discover_tools")), Map.of());
            assertEquals(1, EmailApprovalGate.overlay(gate, mcp, Map.of("discover_tools", "mcp"), Map.of()).gated().size());
            var unknown = new ToolApprovalGate.GateResult(List.of(), List.of(call("c1", "fetch_tool_response_page")), Map.of());
            assertEquals(1, EmailApprovalGate.overlay(gate, unknown, Map.of(), Map.of()).gated().size(),
                    "a loop helper whose source is unknown is gated: only the built-in one is exempt");
        }

        @Test
        @DisplayName("no gate: the agent's split is returned as it is")
        void noGate() {
            var agentSplit = new ToolApprovalGate.GateResult(List.of(), List.of(call("c1", "createOrder")), Map.of());
            assertSame(agentSplit, EmailApprovalGate.overlay(null, agentSplit, SOURCES, ENDPOINTS));
        }

        @Test
        @DisplayName("everything read-only: the split is unchanged")
        void allReadOnly() {
            var agentSplit = new ToolApprovalGate.GateResult(List.of(), List.of(call("c1", "calculate")), Map.of());
            var gate = EmailApprovalGate.of(memory(TurnOrigin.EMAIL, actions("builtin:*")));
            assertSame(agentSplit, EmailApprovalGate.overlay(gate, agentSplit, SOURCES, ENDPOINTS));
        }

        @Test
        @DisplayName("an armed gate gives the loop a config to pause with, even when the agent has none")
        void ensureConfig() {
            var gate = EmailApprovalGate.of(memory(TurnOrigin.EMAIL, actions()));
            assertNotNull(EmailApprovalGate.ensureConfig(gate, null));
            assertNull(EmailApprovalGate.ensureConfig(null, null));
            var agent = new ToolApprovalsConfig();
            assertSame(agent, EmailApprovalGate.ensureConfig(gate, agent));
        }
    }

    @Nested
    @DisplayName("rule-driven calls")
    class RuleCalls {

        @Test
        @DisplayName("a tainted rule-driven httpcall is refused unless readOnlyTools names it")
        void httpCall() {
            var memory = memory(TurnOrigin.EMAIL, actions("lookup"));
            assertTrue(EmailApprovalGate.refusesRuleCall(memory, EmailApprovalGate.httpCallAddresses("createOrder", "POST", "orders")));
            assertFalse(EmailApprovalGate.refusesRuleCall(memory, EmailApprovalGate.httpCallAddresses("lookup", "GET", "/x")));
        }

        @Test
        @DisplayName("endpoint patterns address rule-driven httpcalls as they address http tools")
        void endpointPattern() {
            var memory = memory(TurnOrigin.EMAIL, actions("http.get:*"));
            assertFalse(EmailApprovalGate.refusesRuleCall(memory, EmailApprovalGate.httpCallAddresses("lookup", "GET", "orders")));
            assertTrue(EmailApprovalGate.refusesRuleCall(memory, EmailApprovalGate.httpCallAddresses("create", "POST", "orders")));
        }

        @Test
        @DisplayName("an untainted turn's rule-driven calls are unaffected")
        void untainted() {
            assertFalse(EmailApprovalGate.refusesRuleCall(memory(null, actions()), EmailApprovalGate.httpCallAddresses("x", "POST", "/x")));
        }

        @Test
        @DisplayName("mcpcalls are addressed as mcp:<tool>, the tool and the call name")
        void mcpAddresses() {
            assertEquals(List.of("mcp:search", "search", "find"), EmailApprovalGate.mcpCallAddresses("find", "search"));
        }
    }

    @Nested
    @DisplayName("pause before the agent runs")
    class PreRun {

        @Test
        @DisplayName("asked for by the data supplied for this run, not by the step")
        void fromSuppliedData() {
            var memory = memory(TurnOrigin.EMAIL, null);
            Context preRun = EmailApprovalGate.contextValue("everyTurn", List.of(), true, null, "why");
            List<IData<?>> data = List.of(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, preRun));
            var gate = EmailApprovalGate.preRunRequested(memory, data, true);
            assertNotNull(gate);
            assertEquals("why", gate.pauseReason());
            assertNull(EmailApprovalGate.preRunRequested(memory, List.of(), false), "a rerun without the context does not pause");
        }

        @Test
        @DisplayName("a gate without preRun does not pause before the run")
        void notPreRun() {
            List<IData<?>> data = List.of(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, actions()));
            assertNull(EmailApprovalGate.preRunRequested(memory(TurnOrigin.EMAIL, null), data, true));
        }

        @Test
        @DisplayName("an untainted turn never pauses before the run")
        void untainted() {
            Context preRun = EmailApprovalGate.contextValue("everyTurn", List.of(), true, null, null);
            List<IData<?>> data = List.of(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, preRun));
            assertNull(EmailApprovalGate.preRunRequested(memory(null, null), data, true));
        }

        @Test
        @DisplayName("everyTurn: a new turn without a gate of its own (a continuation) inherits the pause; a rerun does not")
        void everyTurnInherited() {
            var memory = memory(TurnOrigin.EMAIL, null);
            when(memory.getEmailApprovalGate()).thenReturn(Map.of("policy", "everyTurn", "readOnlyTools", List.of(), "rejectedReply", "No."));

            var gate = EmailApprovalGate.preRunRequested(memory, List.of(), true);
            assertNotNull(gate);
            assertTrue(gate.preRun());
            assertEquals("No.", gate.rejectedReply());
            assertNull(EmailApprovalGate.preRunRequested(memory, List.of(), false), "a rerun or the start inherits nothing");
        }

        @Test
        @DisplayName("firstContact and actions are not inherited as a pause; a fresh gate without preRun wins over the record")
        void othersNotInherited() {
            var memory = memory(TurnOrigin.EMAIL, null);
            when(memory.getEmailApprovalGate()).thenReturn(Map.of("policy", "firstContact"));
            assertNull(EmailApprovalGate.preRunRequested(memory, List.of(), true));

            when(memory.getEmailApprovalGate()).thenReturn(Map.of("policy", "everyTurn"));
            Context approvedStart = EmailApprovalGate.contextValue("everyTurn", List.of(), false, null, null);
            List<IData<?>> data = List.of(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, approvedStart));
            assertNull(EmailApprovalGate.preRunRequested(memory, data, true));
        }
    }

    @Nested
    @DisplayName("later turns without a gate of their own")
    class Recorded {

        @Test
        @DisplayName("a continuation of a gated mail conversation is gated by the recorded gate")
        void recordedGateApplies() {
            var memory = memory(TurnOrigin.EMAIL, null);
            when(memory.getEmailApprovalGate()).thenReturn(Map.of("policy", "actions", "readOnlyTools", List.of("lookup")));

            var gate = EmailApprovalGate.of(memory);
            assertNotNull(gate);
            assertEquals(List.of("lookup"), gate.readOnlyTools());
            assertTrue(EmailApprovalGate.refusesRuleCall(memory, List.of("createOrder")));
            assertFalse(EmailApprovalGate.refusesRuleCall(memory, List.of("lookup")));
        }

        @Test
        @DisplayName("a recorded none is no gate; a fresh gate on the step wins over the record")
        void recordedNoneAndFreshWins() {
            var none = memory(TurnOrigin.EMAIL, null);
            when(none.getEmailApprovalGate()).thenReturn(Map.of("policy", "none"));
            stepsWithIntegration(none);
            assertNull(EmailApprovalGate.of(none));

            var fresh = memory(TurnOrigin.EMAIL, EmailApprovalGate.contextValue("none", List.of(), false, null, null));
            when(fresh.getEmailApprovalGate()).thenReturn(Map.of("policy", "actions"));
            assertNull(EmailApprovalGate.of(fresh));
        }

        @Test
        @DisplayName("a mail conversation without a recorded gate fails closed; a delegation without the channel's marker has none")
        void unknownGate() {
            var mailConversation = memory(TurnOrigin.EMAIL, null);
            stepsWithIntegration(mailConversation);
            var gate = EmailApprovalGate.of(mailConversation);
            assertNotNull(gate);
            assertEquals("actions", gate.policy());
            assertTrue(gate.readOnlyTools().isEmpty());

            var delegation = memory(TurnOrigin.EMAIL, null);
            var steps = mock(IConversationMemory.IConversationStepStack.class);
            when(steps.getExactDataPerStep(EmailApprovalGate.INTEGRATION_STEP_KEY)).thenReturn(Arrays.asList(null, null));
            when(delegation.getAllSteps()).thenReturn(steps);
            assertNull(EmailApprovalGate.of(delegation));
        }

        @Test
        @DisplayName("the recorded gate never arms an untainted conversation")
        void untainted() {
            var memory = memory(null, null);
            when(memory.getEmailApprovalGate()).thenReturn(Map.of("policy", "actions"));
            stepsWithIntegration(memory);
            assertNull(EmailApprovalGate.of(memory));
        }

        @Test
        @DisplayName("record: a tainted mail turn's gate is recorded without preRun and the held mail; none is recorded too")
        void recordsGate() {
            var memory = memory(null, null);
            Context gate = EmailApprovalGate.contextValue("everyTurn", List.of("lookup"), true, "No.", "why", Map.of("subject", "hi"));
            List<IData<?>> data = List.of(new Data<>("context:" + TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, "email")),
                    new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, gate));

            EmailApprovalGate.record(memory, data);

            verify(memory).setEmailApprovalGate(
                    Map.of("policy", "everyTurn", "readOnlyTools", List.of("lookup"), "rejectedReply", "No.", "pauseReason", "why"));

            var none = memory(TurnOrigin.EMAIL, null);
            EmailApprovalGate.record(none, List.of(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY,
                    EmailApprovalGate.contextValue("none", List.of(), false, null, null))));
            verify(none).setEmailApprovalGate(Map.of("policy", "none"));
        }

        @Test
        @DisplayName("record: an untainted turn records nothing")
        void untaintedRecordsNothing() {
            var memory = memory(null, null);
            EmailApprovalGate.record(memory, List.of(new Data<>("context:" + EmailApprovalGate.CONTEXT_KEY, actions())));
            verify(memory, never()).setEmailApprovalGate(any());
        }

        private static void stepsWithIntegration(IConversationMemory memory) {
            var steps = mock(IConversationMemory.IConversationStepStack.class);
            IData<Object> marker = new Data<>(EmailApprovalGate.INTEGRATION_STEP_KEY, new Context(Context.ContextType.string, "int1"));
            when(steps.getExactDataPerStep(EmailApprovalGate.INTEGRATION_STEP_KEY)).thenReturn(Arrays.asList(marker, null));
            when(memory.getAllSteps()).thenReturn(steps);
        }
    }

    @Nested
    @DisplayName("read-only-only (a held mail released without an aligned pass, v3 plan §7.3)")
    class ReadOnlyOnly {

        @Test
        @DisplayName("arms the gate even under policy none, and survives parsing and the sticky record")
        void armedAndSticky() {
            Context value = EmailApprovalGate.contextValue("none", List.of("http.get:*"), false, null, null, null, true);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value.getValue();
            assertEquals("actions", map.get("policy"));
            assertEquals(true, map.get("readOnlyOnly"));
            EmailApprovalGate.Policy parsed = EmailApprovalGate.parse(value);
            assertNotNull(parsed);
            assertTrue(parsed.readOnlyOnly());
            assertTrue(EmailApprovalGate.parse(EmailApprovalGate.recordable(parsed)).readOnlyOnly(), "later turns stay restricted");
            assertFalse(EmailApprovalGate.parse(EmailApprovalGate.contextValue("actions", List.of(), false, null, null)).readOnlyOnly());
            assertNull(EmailApprovalGate.parse(EmailApprovalGate.contextValue("none", List.of(), false, null, null, null, false)));
        }

        @Test
        @DisplayName("every gated call is refused, none paused; an ordinary gate refuses nothing")
        void refusesGated() {
            ToolExecutionRequest call = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{}").build();
            var result = new ToolApprovalGate.GateResult(List.of(call), List.of(), Map.of());
            EmailApprovalGate.Policy restricted = EmailApprovalGate.parse(EmailApprovalGate.contextValue("actions", List.of(), false, null, null,
                    null, true));
            assertEquals(List.of(call), EmailApprovalGate.refusedAsReadOnlyOnly(restricted, result));
            assertTrue(EmailApprovalGate.refusedAsReadOnlyOnly(EmailApprovalGate.parse(EmailApprovalGate.contextValue("actions", List.of(), false,
                    null, null)), result).isEmpty());
            assertTrue(EmailApprovalGate.refusedAsReadOnlyOnly(null, result).isEmpty());
        }
    }
}