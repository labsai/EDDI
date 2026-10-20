/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agenttests.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * A suite of "golden conversations" for one agent: scripted turns, what each
 * turn is expected to do, and the tool responses to substitute while it runs.
 * <p>
 * Stored as a versioned configuration document like every other config, so a
 * suite has a history and can travel with its agent later. The runner that
 * executes it is {@code AgentTestRunner}; this record is pure data.
 * <p>
 * Every field is optional on the wire. Absent means the default documented on
 * the field, which is what {@link Defaults#EFFECTIVE} spells out — a suite
 * written before a field existed runs exactly as it did.
 *
 * @param name
 *            human-readable name of the suite
 * @param description
 *            free text for the author
 * @param defaults
 *            per-case defaults (tool mode, repetitions, pass rate, timeouts)
 * @param maxCostPerRun
 *            dollar ceiling for one run of the whole suite, checked between
 *            turns; default {@value #DEFAULT_MAX_COST_PER_RUN}
 * @param toolMocks
 *            the tool responses a run substitutes for real calls
 * @param cases
 *            the scripted conversations
 * @since 6.5.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentTestSuite(String name, String description, Defaults defaults, Double maxCostPerRun, List<ToolMock> toolMocks,
        List<TestCase> cases) {

    /** Dollar ceiling for one run when {@link #maxCostPerRun} is absent. */
    public static final double DEFAULT_MAX_COST_PER_RUN = 1.0;

    /**
     * Substitute mocks for tool calls; unmocked calls follow {@code unmockedTools}.
     */
    public static final String TOOL_MODE_MOCK = "mock";

    /** Every tool call runs for real. An explicit opt-in. */
    public static final String TOOL_MODE_LIVE = "live";

    /** An unmocked call is not executed and fails the case. */
    public static final String UNMOCKED_FAIL = "fail";

    /** An unmocked call runs for real. An explicit opt-in. */
    public static final String UNMOCKED_LIVE = "live";

    public double effectiveMaxCostPerRun() {
        return maxCostPerRun != null ? maxCostPerRun : DEFAULT_MAX_COST_PER_RUN;
    }

    public Defaults effectiveDefaults() {
        return Defaults.EFFECTIVE.overriddenBy(defaults);
    }

    /**
     * Per-case defaults. A case may override {@code repetitions} and
     * {@code passRate}.
     *
     * @param toolMode
     *            {@value AgentTestSuite#TOOL_MODE_MOCK} (default) or
     *            {@value AgentTestSuite#TOOL_MODE_LIVE}
     * @param unmockedTools
     *            {@value AgentTestSuite#UNMOCKED_FAIL} (default) or
     *            {@value AgentTestSuite#UNMOCKED_LIVE}
     * @param repetitions
     *            how often each case runs (default 1)
     * @param passRate
     *            fraction of repetitions that must pass, 0..1 (default 1.0)
     * @param turnTimeoutSeconds
     *            how long one turn may take before it counts as failed (default
     *            120)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Defaults(String toolMode, String unmockedTools, Integer repetitions, Double passRate, Integer turnTimeoutSeconds) {

        public static final Defaults EFFECTIVE = new Defaults(TOOL_MODE_MOCK, UNMOCKED_FAIL, 1, 1.0, 120);

        /** This record with every non-null field of {@code other} applied on top. */
        public Defaults overriddenBy(Defaults other) {
            if (other == null) {
                return this;
            }
            return new Defaults(other.toolMode != null ? other.toolMode : toolMode,
                    other.unmockedTools != null ? other.unmockedTools : unmockedTools,
                    other.repetitions != null ? other.repetitions : repetitions,
                    other.passRate != null ? other.passRate : passRate,
                    other.turnTimeoutSeconds != null ? other.turnTimeoutSeconds : turnTimeoutSeconds);
        }
    }

    /**
     * A substitute response for a tool call.
     *
     * @param tool
     *            the tool name as the agent calls it — an httpcall's {@code name},
     *            an MCP tool name, a built-in tool name
     * @param argsSubset
     *            only calls whose arguments contain these key/values match; absent
     *            matches any call. Applies to LLM tool calls — rule-triggered
     *            httpcalls and MCP calls match on {@code tool} alone
     * @param response
     *            what the call returns instead of running
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolMock(String tool, Map<String, Object> argsSubset, MockResponse response) {
    }

    /**
     * The shape an httpcall returns — {@code httpCode} and {@code body} — used for
     * every tool source: an MCP or built-in tool receives {@code body} (a string
     * as-is, anything else as JSON).
     *
     * @param httpCode
     *            default 200
     * @param body
     *            string, number, object or array
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MockResponse(Integer httpCode, Object body) {
        public int effectiveHttpCode() {
            return httpCode != null ? httpCode : 200;
        }
    }

    /**
     * One scripted conversation.
     *
     * @param name
     *            shown in results
     * @param context
     *            context values sent with every turn (string → string context, map
     *            → object, list → array)
     * @param repetitions
     *            overrides {@link Defaults#repetitions}
     * @param passRate
     *            overrides {@link Defaults#passRate}
     * @param turns
     *            the user inputs and their expectations, in order
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestCase(String name, Map<String, Object> context, Integer repetitions, Double passRate, List<Turn> turns) {
    }

    /**
     * One user input.
     *
     * @param input
     *            the text the user sends
     * @param context
     *            extra context values for this turn only, on top of the case's
     * @param expect
     *            what must be true after the turn; absent asserts nothing
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Turn(String input, Map<String, Object> context, Expectation expect) {
    }

    /**
     * Assertions on one turn. Every present field must hold.
     *
     * @param actionsContain
     *            every listed action fired this turn
     * @param actionsExact
     *            exactly these actions fired this turn, in this order
     * @param properties
     *            conversation property name → condition, read after the turn
     * @param outputContains
     *            the turn's output text contains this (case-sensitive)
     * @param outputMatches
     *            the turn's output text matches this regex (find, not full match)
     * @param toolCalled
     *            each listed tool was called this turn (optionally with these
     *            arguments)
     * @param toolNotCalled
     *            none of these tools was called this turn
     * @param maxToolCalls
     *            at most this many tool calls this turn
     * @param maxCostUsd
     *            the turn cost at most this much
     * @param conversationState
     *            the conversation state after the turn, e.g. {@code READY},
     *            {@code AWAITING_HUMAN}, {@code ENDED}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Expectation(List<String> actionsContain, List<String> actionsExact, Map<String, PropertyExpectation> properties,
            String outputContains, String outputMatches, List<ToolCallExpectation> toolCalled, List<String> toolNotCalled,
            Integer maxToolCalls, Double maxCostUsd, String conversationState) {
    }

    /**
     * A condition on one conversation property. Exactly one field is expected to be
     * set; when several are, all must hold.
     *
     * @param equalTo
     *            JSON {@code equals}: the property's value equals this (string,
     *            number, boolean, object or list)
     * @param matches
     *            the property's value, as text, matches this regex
     * @param exists
     *            {@code true}: the property is set
     * @param absent
     *            {@code true}: the property is not set
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PropertyExpectation(@JsonProperty("equals") Object equalTo, String matches, Boolean exists, Boolean absent) {
    }

    /**
     * @param tool
     *            the tool name
     * @param argsSubset
     *            the call's arguments contain these key/values; absent matches any
     *            call of the tool
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCallExpectation(String tool, Map<String, Object> argsSubset) {
    }
}
