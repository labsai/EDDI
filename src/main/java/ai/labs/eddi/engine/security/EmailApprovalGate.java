/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalPatterns;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.model.Context;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The email approval gate (email plan v3 §8.1): its own gate, applied to a turn
 * a mail started after the agent's own {@code toolApprovals} have been resolved
 * ({@code TaskToolApprovalsResolver}), and never merged into them.
 * <p>
 * Merging {@code requireApproval: ["*"]} into the agent's block would be undone
 * by the agent's {@code exempt} (an exemption always wins), by
 * {@code task-approvals.mode=replace} and by an agent
 * {@code timeoutPolicy: AUTO_APPROVE}. So instead:
 * <ul>
 * <li><b>Tool calls</b> of the LLM task: after the agent's gate split a batch,
 * every call it <em>allowed</em> that is not in the target's
 * {@code readOnlyTools} moves to the gated side ({@link #overlay}). Never the
 * other way: {@code readOnlyTools} cannot un-gate what the agent gates. Such a
 * pause times out as {@code AUTO_REJECT} (see
 * {@code ConversationHitlService}).</li>
 * <li><b>Rule-driven calls</b> ({@code httpcalls}, {@code mcpcalls}) have no
 * loop to pause in, so they are refused unless named in {@code readOnlyTools}
 * ({@link #refusesRuleCall}).</li>
 * <li><b>Before the agent runs</b> ({@code firstContact}, {@code everyTurn}):
 * the conversation pauses before its first task, with no step run yet — before
 * {@code CONVERSATION_START} for a new conversation
 * ({@link #preRunRequested}).</li>
 * </ul>
 * The target's settings reach the turn in the reserved context key
 * {@link #CONTEXT_KEY}, which only the email channel writes
 * ({@link ClientContextGuard} removes it from client context) and which is read
 * exactly, on the current step only — so a HITL resume next week reads what the
 * mail's turn carried. The gate is armed only for a turn that is also tainted
 * {@code origin=email} ({@link TurnOrigin}). A conversation a mail turn starts
 * (a delegation, a group member) is tainted but carries no target, so it has no
 * email gate of its own: the delegating tool call is itself gated.
 */
public final class EmailApprovalGate {

    /** The reserved context key the email channel puts the target's gate in. */
    public static final String CONTEXT_KEY = "emailApproval";

    /** Exact step-data key of {@link #CONTEXT_KEY}. */
    static final String STEP_KEY = "context:" + CONTEXT_KEY;

    /**
     * Step datum written when a turn paused before it ran: the resume reads it to
     * apply {@code rejectedReply} on a rejection.
     */
    public static final String PRE_RUN_MARKER = "emailGate:preRun";

    /**
     * Conversation-output key written on the step a pre-run rejection answered, so
     * the email reply path sends {@code rejectedReply} instead of a generic text.
     */
    public static final String REJECTED_OUTPUT_KEY = "emailGate";
    public static final String REJECTED_OUTPUT_VALUE = "rejected";

    /** Gate reason of a call the email gate gated. */
    public static final String GATE_REASON = "email-gate:not-read-only";

    /**
     * EDDI's own loop helpers, which only read what the turn already has: LAZY tool
     * discovery and the page of a truncated tool response. Never gated by the email
     * gate when they are the built-in ones — pausing them would hold every answer
     * of a LAZY agent while changing nothing.
     */
    static final Set<String> LOOP_CONTROL_TOOLS = Set.of("discover_tools", "fetch_tool_response_page");

    private static boolean isLoopControl(String name, Map<String, String> toolSources) {
        String source = toolSources != null ? toolSources.get(name) : null;
        return LOOP_CONTROL_TOOLS.contains(name) && (source == null || "builtin".equals(source));
    }

    /** Pause reason when the mail itself waits before the agent runs. */
    public static final String DEFAULT_PRE_RUN_REASON = "A mail is waiting for approval before the agent runs.";

    private static final String FIELD_POLICY = "policy";
    private static final String FIELD_READ_ONLY = "readOnlyTools";
    private static final String FIELD_PRE_RUN = "preRun";
    private static final String FIELD_REJECTED_REPLY = "rejectedReply";
    private static final String FIELD_PAUSE_REASON = "pauseReason";

    private EmailApprovalGate() {
    }

    /**
     * The gate a mail turn carries.
     *
     * @param policy
     *            the target's {@code approvalPolicy} JSON name, never {@code none}
     * @param readOnlyTools
     *            patterns of the calls that may run without the gate
     * @param preRun
     *            whether this turn pauses before the agent runs
     * @param rejectedReply
     *            what a rejected pre-run pause answers, or null for nothing
     * @param pauseReason
     *            what the approver is shown for a pre-run pause
     */
    public record Policy(String policy, List<String> readOnlyTools, boolean preRun, String rejectedReply, String pauseReason) {

        public Policy {
            readOnlyTools = readOnlyTools == null ? List.of() : List.copyOf(readOnlyTools);
        }

        /** Whether one of {@code addresses} matches a {@code readOnlyTools} pattern. */
        public boolean isReadOnly(List<String> addresses) {
            for (String pattern : readOnlyTools) {
                for (String address : addresses) {
                    if (ToolApprovalPatterns.matches(pattern, address)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * The context value the email channel writes for a target: a map, because a
     * {@link Context} carries one value. Null for a target without a gate, so the
     * key is not written at all.
     */
    public static Context contextValue(String policy, List<String> readOnlyTools, boolean preRun, String rejectedReply,
                                       String pauseReason) {
        if (policy == null || policy.isBlank() || "none".equalsIgnoreCase(policy)) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(FIELD_POLICY, policy);
        value.put(FIELD_READ_ONLY, readOnlyTools == null ? List.of() : new ArrayList<>(readOnlyTools));
        value.put(FIELD_PRE_RUN, preRun);
        if (rejectedReply != null) {
            value.put(FIELD_REJECTED_REPLY, rejectedReply);
        }
        if (pauseReason != null) {
            value.put(FIELD_PAUSE_REASON, pauseReason);
        }
        return new Context(Context.ContextType.object, value);
    }

    /**
     * The gate of the turn {@code memory} is running, or null: none when the
     * conversation is not tainted {@code origin=email}, or the current step carries
     * no gate.
     */
    public static Policy of(IConversationMemory memory) {
        if (memory == null) {
            return null;
        }
        try {
            var step = memory.getCurrentStep();
            IData<Object> data = step == null ? null : step.getData(STEP_KEY);
            if (data == null || !(isTainted(memory) || EMAIL_TAINT.equals(stepTaint(step.getData(TAINT_STEP_KEY))))) {
                return null;
            }
            return parse(data.getResult());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Exact step-data key of the turn's own taint ({@link TurnOrigin#CONTEXT_KEY}).
     */
    static final String TAINT_STEP_KEY = "context:" + TurnOrigin.CONTEXT_KEY;
    private static final String EMAIL_TAINT = TurnOrigin.EMAIL;

    /**
     * The taint the turn's own context carries. Read as well as the memory's and
     * the thread's: a new conversation's {@code CONVERSATION_START} turn runs
     * before the memory records the taint, and a cascade step can run on a pool
     * thread with no principal bound — the context is on the step in both cases.
     */
    private static String stepTaint(IData<?> datum) {
        Object value = datum == null ? null : datum.getResult();
        if (value instanceof Context context) {
            value = context.getValue();
        } else if (value instanceof Map<?, ?> map) {
            value = map.get("value");
        }
        return value == null ? null : TurnOrigin.normalise(String.valueOf(value));
    }

    /**
     * Whether the data a turn is about to run with asks for a pause before the
     * agent runs. Read from the data supplied for this run, not from the step, so a
     * rerun of a step that once paused does not pause again.
     */
    public static Policy preRunRequested(IConversationMemory memory, List<IData<?>> lifecycleData) {
        if (memory == null || lifecycleData == null) {
            return null;
        }
        boolean tainted = isTainted(memory);
        Policy requested = null;
        for (IData<?> datum : lifecycleData) {
            if (datum == null) {
                continue;
            }
            if (STEP_KEY.equals(datum.getKey())) {
                Policy policy = parse(datum.getResult());
                requested = policy != null && policy.preRun() ? policy : null;
            } else if (TAINT_STEP_KEY.equals(datum.getKey()) && EMAIL_TAINT.equals(stepTaint(datum))) {
                tainted = true;
            }
        }
        return tainted ? requested : null;
    }

    /**
     * A copy of the turn's tool-approval config the tool loop can pause with while
     * the email gate is armed: an empty config instead of none (an agent without
     * {@code toolApprovals}, or the tool-HITL kill switch), so the pause machinery
     * (call ids, the pause budget, the batch) is in force. What the agent's gate
     * itself gates is unchanged: an empty {@code requireApproval} gates nothing.
     */
    public static ToolApprovalsConfig ensureConfig(Policy gate, ToolApprovalsConfig effective) {
        if (gate == null || effective != null) {
            return effective;
        }
        return new ToolApprovalsConfig();
    }

    /**
     * The agent gate's split of a batch, with every allowed call that is not
     * read-only moved to the gated side. Calls the agent gated stay gated.
     */
    public static ToolApprovalGate.GateResult overlay(Policy gate, ToolApprovalGate.GateResult result, Map<String, String> toolSources,
                                                      Map<String, String> toolEndpoints) {
        if (gate == null || result == null || result.allowed().isEmpty()) {
            return result;
        }
        List<ToolExecutionRequest> gated = new ArrayList<>(result.gated());
        List<ToolExecutionRequest> allowed = new ArrayList<>();
        Map<String, String> reasons = new HashMap<>(result.gateReasonByCallId());
        for (ToolExecutionRequest request : result.allowed()) {
            if (request.name() == null) {
                // A malformed call names no tool; it runs nothing ("tool not found").
                allowed.add(request);
                continue;
            }
            List<String> addresses = ToolApprovalGate.addressesOf(request.name(), toolSources != null ? toolSources : Map.of(),
                    toolEndpoints != null ? toolEndpoints : Map.of());
            if (gate.isReadOnly(addresses) || isLoopControl(request.name(), toolSources)) {
                allowed.add(request);
            } else {
                gated.add(request);
                if (request.id() != null && !request.id().isBlank()) {
                    reasons.put(request.id(), GATE_REASON);
                }
            }
        }
        if (allowed.size() == result.allowed().size()) {
            return result;
        }
        return new ToolApprovalGate.GateResult(gated, allowed, reasons);
    }

    /**
     * Whether a rule-driven call ({@code httpcalls}, {@code mcpcalls}) of the turn
     * {@code memory} is running must be refused: the email gate is armed and none
     * of the call's {@code addresses} is read-only.
     */
    public static boolean refusesRuleCall(IConversationMemory memory, List<String> addresses) {
        Policy gate = of(memory);
        return gate != null && !gate.isReadOnly(addresses);
    }

    /**
     * The addresses of a rule-driven httpcall, as the LLM loop addresses its http
     * tools: {@code http.<method>:<path>}, {@code http:<name>}, {@code <name>}.
     */
    public static List<String> httpCallAddresses(String name, String method, String path) {
        List<String> addresses = new ArrayList<>(3);
        if (method != null && path != null) {
            String normalised = path.startsWith("/") ? path : "/" + path;
            addresses.add("http." + method.toLowerCase(Locale.ROOT) + ":" + normalised);
        }
        if (name != null) {
            addresses.add("http:" + name);
            addresses.add(name);
        }
        return addresses;
    }

    /**
     * The addresses of a rule-driven mcpcall: {@code mcp:<tool>}, {@code <tool>}
     * and the call's own name.
     */
    public static List<String> mcpCallAddresses(String callName, String toolName) {
        List<String> addresses = new ArrayList<>(3);
        if (toolName != null) {
            addresses.add("mcp:" + toolName);
            addresses.add(toolName);
        }
        if (callName != null && !callName.equals(toolName)) {
            addresses.add(callName);
        }
        return addresses;
    }

    /**
     * Whether the paused tool batch holds a call this gate gated.
     */
    public static boolean gatedByEmail(Map<String, String> gateReasonByCallId) {
        return gateReasonByCallId != null && gateReasonByCallId.containsValue(GATE_REASON);
    }

    private static boolean isTainted(IConversationMemory memory) {
        return TurnOrigin.EMAIL.equals(memory.getOrigin()) || TurnOrigin.currentIsEmail();
    }

    @SuppressWarnings("unchecked")
    static Policy parse(Object stored) {
        Object value = stored;
        if (value instanceof Context context) {
            value = context.getValue();
        } else if (value instanceof Map<?, ?> map && map.containsKey("value") && !map.containsKey(FIELD_POLICY)) {
            value = map.get("value");
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        Object policy = map.get(FIELD_POLICY);
        if (policy == null || "none".equalsIgnoreCase(String.valueOf(policy))) {
            return null;
        }
        List<String> readOnly = new ArrayList<>();
        if (map.get(FIELD_READ_ONLY) instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null && !String.valueOf(entry).isBlank()) {
                    readOnly.add(String.valueOf(entry).trim());
                }
            }
        }
        Object preRun = map.get(FIELD_PRE_RUN);
        Object rejected = map.get(FIELD_REJECTED_REPLY);
        Object reason = map.get(FIELD_PAUSE_REASON);
        return new Policy(String.valueOf(policy), readOnly, Boolean.TRUE.equals(preRun) || "true".equalsIgnoreCase(String.valueOf(preRun)),
                rejected == null ? null : String.valueOf(rejected), reason == null ? null : String.valueOf(reason));
    }
}
