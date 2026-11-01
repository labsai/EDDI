/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolMock;
import ai.labs.eddi.engine.memory.IConversationMemory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where an agent-test conversation's tool calls are answered instead of
 * executed.
 * <p>
 * Consulted at the three places a pipeline reaches the outside world: every LLM
 * tool call ({@code ToolLoopRunner.executeSingleToolCallResult}, all sources),
 * rule-triggered httpcalls ({@code ApiCallExecutor.execute}) and rule-triggered
 * MCP calls ({@code McpCallsTask}). For any conversation that is not an agent
 * test the whole cost is one string prefix check on its user id
 * ({@link AgentTestConversations#isTestUserId}) and the call proceeds
 * untouched.
 * <h3>Fail closed</h3> A test conversation's call runs for real only when its
 * suite explicitly opted in ({@code toolMode: live} or
 * {@code unmockedTools: live}). Otherwise it gets the matching mock, or — with
 * no mock — {@link #NOT_EXECUTED_JSON}, which the runner reports as a failed
 * case. That includes a test conversation the registry no longer knows (a turn
 * still running after its run gave up on it): no session means no permission.
 * Tools that write long-term user memory ({@code memory} source) never run live
 * in a test, whatever the suite says.
 * <h3>Why static</h3> The LLM tool loop is plain classes constructed by
 * {@code AgentOrchestrator}, not CDI beans; threading a bean through it would
 * change a dozen constructors for a lookup that is process-global by nature.
 *
 * @since 6.5.0
 */
public final class ToolMockRegistry {

    /** What an unmocked call answers when unmocked calls fail. */
    public static final String NOT_EXECUTED_JSON = "{\"status\":\"NOT_EXECUTED\",\"reason\":\"no mock defined in test mode\"}";

    /**
     * What a call answers when this node holds no session for its test
     * conversation: the run that owns it has finished, or the turn ran on a
     * different node. Worded differently from {@link #NOT_EXECUTED_JSON} so the two
     * can be told apart in a result.
     */
    public static final String UNKNOWN_SESSION_JSON = "{\"status\":\"NOT_EXECUTED\",\"reason\":\"no agent-test session on this node "
            + "(the run owning this conversation has ended, or the turn ran on another cluster node)\"}";

    /** What a memory-writing tool answers when the suite runs tools live. */
    public static final String MEMORY_BLOCKED_JSON = "{\"status\":\"NOT_EXECUTED\",\"reason\":\"agent tests never write user memory\"}";

    /** The status an unmocked rule-triggered httpcall is answered with. */
    public static final int NOT_EXECUTED_HTTP_CODE = 501;

    /**
     * Template-data key the LLM tool path sets on an httpcall it hands to the
     * executor after {@link #interceptToolCall} has already decided (and recorded)
     * that call. The executor then skips {@link #interceptRuleCall}, so a live tool
     * call is neither recorded nor mocked a second time.
     */
    public static final String TOOL_PATH_MARKER = "__agentTestToolPathIntercepted";

    /**
     * Template-data key carrying the {@link MockResult} the LLM tool path chose for
     * an httpcall tool. The executor answers the call with it instead of sending a
     * request, and runs everything else — {@code saveResponse}, retries,
     * {@code postResponse} property instructions — on it exactly as on a real
     * response, the same way a rule-triggered call is mocked.
     */
    public static final String TOOL_PATH_MOCK = "__agentTestToolPathMock";

    static final String SOURCE_HTTP = "http";
    static final String SOURCE_MEMORY = "memory";

    /**
     * Engine-internal control tools that reach nothing outside: LAZY tool discovery
     * only changes which specs the model sees.
     */
    private static final Set<String> INTERNAL_TOOLS = Set.of("discover_tools");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private ToolMockRegistry() {
    }

    /** How one call was answered. */
    public enum Outcome {
        /** a mock answered it */
        MOCKED,
        /** no mock and unmocked calls fail: not executed, the case fails */
        NOT_EXECUTED,
        /** a memory-writing tool in a live suite: not executed, not a failure */
        BLOCKED,
        /** executed for real, as the suite opted in to */
        LIVE
    }

    /**
     * One intercepted call.
     *
     * @param turn
     *            the turn it happened in (0 = the start turn)
     * @param tool
     *            the tool or httpcall name
     * @param arguments
     *            its JSON arguments, may be null
     * @param source
     *            {@code http}, {@code mcp}, {@code builtin}, … or {@code httpcall}
     *            / {@code mcpcall} for rule-triggered calls
     * @param outcome
     *            how it was answered
     */
    public record RecordedCall(int turn, String tool, String arguments, String source, Outcome outcome) {
    }

    /** The answer a call gets instead of being executed. */
    public record MockResult(int httpCode, Object body, Outcome outcome) {

        /** The body as text: a string as-is, anything else as JSON. */
        public String bodyAsText() {
            if (body == null) {
                return "";
            }
            if (body instanceof String text) {
                return text;
            }
            return toJson(body);
        }

        /** Whether the body is structured (JSON) rather than text. */
        public boolean isJsonBody() {
            return body != null && !(body instanceof String);
        }
    }

    /** One test conversation's mocks and the calls it made. */
    public static final class Session {
        private final List<ToolMock> mocks;
        private final boolean mockMode;
        private final boolean unmockedLive;
        private final List<RecordedCall> calls = new ArrayList<>();
        private volatile int turn;

        public Session(AgentTestSuite suite) {
            var defaults = suite.effectiveDefaults();
            this.mocks = suite.toolMocks() != null ? List.copyOf(suite.toolMocks()) : List.of();
            this.mockMode = !AgentTestSuite.TOOL_MODE_LIVE.equals(defaults.toolMode());
            this.unmockedLive = AgentTestSuite.UNMOCKED_LIVE.equals(defaults.unmockedTools());
        }

        /** Calls from now on belong to {@code turn}. */
        public void startTurn(int turn) {
            this.turn = turn;
        }

        public List<RecordedCall> callsOfTurn(int turn) {
            synchronized (calls) {
                return calls.stream().filter(c -> c.turn() == turn).toList();
            }
        }

        public List<RecordedCall> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        boolean allowsLive() {
            return !mockMode || unmockedLive;
        }

        private void record(String tool, String arguments, String source, Outcome outcome) {
            synchronized (calls) {
                calls.add(new RecordedCall(turn, tool, arguments, source, outcome));
            }
        }

        /**
         * @param withArguments
         *            false for rule-triggered calls, which have no model-chosen
         *            arguments: only mocks without {@code argsSubset} match them
         */
        private ToolMock find(String tool, String arguments, boolean withArguments) {
            for (ToolMock mock : mocks) {
                if (!tool.equals(mock.tool())) {
                    continue;
                }
                boolean noSubset = mock.argsSubset() == null || mock.argsSubset().isEmpty();
                if (noSubset || (withArguments && ExpectationEvaluator.argumentsContain(arguments, mock.argsSubset()))) {
                    return mock;
                }
            }
            return null;
        }

        /** null: execute for real. */
        private MockResult answer(String tool, String arguments, String source, boolean withArguments) {
            Decision decision = decide(tool, arguments, source, withArguments);
            record(tool, arguments, source, decision.outcome());
            return decision.result();
        }

        /** How the call would be answered; records nothing. */
        private Decision decide(String tool, String arguments, String source, boolean withArguments) {
            if (!mockMode && !SOURCE_MEMORY.equals(source)) {
                return new Decision(Outcome.LIVE, null);
            }
            ToolMock mock = find(tool, arguments, withArguments);
            if (mock != null) {
                return new Decision(Outcome.MOCKED,
                        new MockResult(mock.response().effectiveHttpCode(), mock.response().body(), Outcome.MOCKED));
            }
            if (allowsLive()) {
                if (SOURCE_MEMORY.equals(source)) {
                    return new Decision(Outcome.BLOCKED, new MockResult(NOT_EXECUTED_HTTP_CODE, MEMORY_BLOCKED_JSON, Outcome.BLOCKED));
                }
                return new Decision(Outcome.LIVE, null);
            }
            return new Decision(Outcome.NOT_EXECUTED, new MockResult(NOT_EXECUTED_HTTP_CODE, NOT_EXECUTED_JSON, Outcome.NOT_EXECUTED));
        }
    }

    /**
     * How a call is to be answered.
     *
     * @param outcome
     *            the outcome to record once the call is made
     * @param result
     *            the answer instead of executing; null for {@link Outcome#LIVE}
     */
    public record Decision(Outcome outcome, MockResult result) {
    }

    /**
     * Whether {@code toolResult} is one of this registry's refusals rather than a
     * mock's answer.
     */
    public static boolean isRefusal(String toolResult) {
        return NOT_EXECUTED_JSON.equals(toolResult) || UNKNOWN_SESSION_JSON.equals(toolResult) || MEMORY_BLOCKED_JSON.equals(toolResult);
    }

    /** Arms mocking for the conversation of {@code userId}. */
    public static void register(String userId, Session session) {
        SESSIONS.put(userId, session);
    }

    /** Disarms it; later calls of that conversation are refused, not executed. */
    public static void unregister(String userId) {
        SESSIONS.remove(userId);
    }

    /**
     * An LLM tool call. {@code null} means "not a test conversation, or the suite
     * runs this call live — execute it"; anything else is the tool result to hand
     * the model.
     *
     * @param source
     *            the tool's source tag ({@code http}, {@code mcp}, {@code builtin},
     *            {@code memory}, …), may be null
     */
    public static String interceptToolCall(IConversationMemory memory, String tool, String arguments, String source) {
        MockResult result = decideToolCall(memory, tool, arguments, source);
        return result == null ? null : toolResultOf(result, source);
    }

    /**
     * How an LLM tool call of a test conversation is answered — decided, and
     * recorded, once. {@code null} means execute it: not a test conversation, an
     * internal tool, or a call the suite runs live.
     *
     * @param source
     *            the tool's source tag, may be null
     */
    public static MockResult decideToolCall(IConversationMemory memory, String tool, String arguments, String source) {
        String userId = memory != null ? memory.getUserId() : null;
        if (!AgentTestConversations.isTestUserId(userId) || INTERNAL_TOOLS.contains(tool)) {
            return null;
        }
        Session session = SESSIONS.get(userId);
        return session != null
                ? session.answer(tool, arguments, source, true)
                : new MockResult(NOT_EXECUTED_HTTP_CODE, UNKNOWN_SESSION_JSON, Outcome.NOT_EXECUTED);
    }

    /**
     * How an LLM tool call of a test conversation would be answered, without
     * recording it — for a caller that has gates of its own to pass first (budget,
     * quota, turn deadline) and records with {@link #recordToolCall} only once the
     * call is really made. {@code null}: not a test conversation, or an internal
     * tool — execute it and record nothing.
     */
    public static Decision previewToolCall(IConversationMemory memory, String tool, String arguments, String source) {
        String userId = memory != null ? memory.getUserId() : null;
        if (!AgentTestConversations.isTestUserId(userId) || INTERNAL_TOOLS.contains(tool)) {
            return null;
        }
        Session session = SESSIONS.get(userId);
        return session != null
                ? session.decide(tool, arguments, source, true)
                : new Decision(Outcome.NOT_EXECUTED, new MockResult(NOT_EXECUTED_HTTP_CODE, UNKNOWN_SESSION_JSON, Outcome.NOT_EXECUTED));
    }

    /**
     * Records a call {@link #previewToolCall} decided, now that it is made. A no-op
     * for a conversation that is not a test, or whose session is gone.
     */
    public static void recordToolCall(IConversationMemory memory, String tool, String arguments, String source, Outcome outcome) {
        String userId = memory != null ? memory.getUserId() : null;
        if (!AgentTestConversations.isTestUserId(userId) || outcome == null) {
            return;
        }
        Session session = SESSIONS.get(userId);
        if (session != null) {
            session.record(tool, arguments, source, outcome);
        }
    }

    /**
     * The tool result a decided call hands the model when no executor runs it: a
     * refusal as its JSON, an httpcall tool's mock in the httpcall result shape
     * ({@code {"httpCode": …, "body": …}}), any other mock as its body.
     */
    public static String toolResultOf(MockResult result, String source) {
        if (result.outcome() != Outcome.MOCKED) {
            return result.bodyAsText();
        }
        if (SOURCE_HTTP.equals(source)) {
            // The shape an httpcall tool hands the model: ApiCallExecutor's result map.
            var shaped = new LinkedHashMap<String, Object>();
            shaped.put("httpCode", result.httpCode());
            if (result.body() != null) {
                shaped.put("body", result.body());
            }
            return toJson(shaped);
        }
        return result.bodyAsText();
    }

    /**
     * A rule-triggered call (httpcall or MCP call), which has no model-chosen
     * arguments. {@code null} means execute it.
     *
     * @param source
     *            {@code httpcall} or {@code mcpcall}
     */
    public static MockResult interceptRuleCall(IConversationMemory memory, String name, String arguments, String source) {
        String userId = memory != null ? memory.getUserId() : null;
        if (!AgentTestConversations.isTestUserId(userId)) {
            return null;
        }
        Session session = SESSIONS.get(userId);
        if (session == null) {
            return new MockResult(NOT_EXECUTED_HTTP_CODE, UNKNOWN_SESSION_JSON, Outcome.NOT_EXECUTED);
        }
        return session.answer(name, arguments, source, false);
    }

    /**
     * Whether a call of this conversation might run for real — false means a
     * rule-triggered MCP call need not even discover the server's tools.
     */
    public static boolean mayRunLive(IConversationMemory memory) {
        String userId = memory != null ? memory.getUserId() : null;
        if (!AgentTestConversations.isTestUserId(userId)) {
            return true;
        }
        Session session = SESSIONS.get(userId);
        return session != null && session.allowsLive();
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
