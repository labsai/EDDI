/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agenttests;

import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Expectation;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.PropertyExpectation;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.TestCase;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.ToolMock;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Turn;
import ai.labs.eddi.engine.agenttests.BoundedRegex;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Save-time checks for an {@link AgentTestSuite}. A violation is the author's
 * mistake, so it is an {@link IllegalArgumentException} — a 400 naming the
 * field, never a 500.
 * <p>
 * The caps exist because a suite is executed: every case × repetition is a real
 * conversation that can call a real model, and the run's result document has to
 * stay well below the 16 MB a MongoDB document may hold. They bound what one
 * run can cost before {@code maxCostPerRun} ever has to.
 *
 * @since 6.5.0
 */
public final class AgentTestSuiteValidator {

    public static final int MAX_CASES = 200;
    public static final int MAX_TURNS_PER_CASE = 50;
    public static final int MAX_REPETITIONS = 20;
    /** Turns one run may execute in total, cases × repetitions × turns. */
    public static final int MAX_TURNS_PER_RUN = 2_000;
    public static final int MAX_TOOL_MOCKS = 500;
    public static final int MAX_INPUT_CHARS = 20_000;
    public static final int MAX_TURN_TIMEOUT_SECONDS = 900;
    public static final double MAX_COST_PER_RUN_CEILING = 100.0;

    /**
     * Longest regex an expectation may carry. Together with the time-bounded
     * matcher the runner uses, this is the ReDoS guard: a short pattern cannot
     * encode much backtracking, and the matcher gives up after a fixed time
     * regardless.
     */
    public static final int MAX_PATTERN_LENGTH = 500;

    private static final Set<String> TOOL_MODES = Set.of(AgentTestSuite.TOOL_MODE_MOCK, AgentTestSuite.TOOL_MODE_LIVE);
    private static final Set<String> UNMOCKED_MODES = Set.of(AgentTestSuite.UNMOCKED_FAIL, AgentTestSuite.UNMOCKED_LIVE);
    private static final Set<String> CONVERSATION_STATES = Set.of("READY", "IN_PROGRESS", "ENDED", "EXECUTION_INTERRUPTED", "ERROR",
            "AWAITING_HUMAN");

    private AgentTestSuiteValidator() {
    }

    /**
     * @throws IllegalArgumentException
     *             naming the first violation
     */
    public static void validate(AgentTestSuite suite) {
        if (suite == null) {
            throw new IllegalArgumentException("A test suite body is required.");
        }
        if (suite.maxCostPerRun() != null && (suite.maxCostPerRun() < 0 || suite.maxCostPerRun() > MAX_COST_PER_RUN_CEILING)) {
            throw new IllegalArgumentException("maxCostPerRun must be between 0 and " + MAX_COST_PER_RUN_CEILING + " (USD).");
        }
        validateDefaults(suite.defaults());

        List<ToolMock> mocks = suite.toolMocks();
        if (mocks != null) {
            if (mocks.size() > MAX_TOOL_MOCKS) {
                throw new IllegalArgumentException("A suite may define at most " + MAX_TOOL_MOCKS + " toolMocks.");
            }
            for (int i = 0; i < mocks.size(); i++) {
                ToolMock mock = mocks.get(i);
                if (mock == null || isBlank(mock.tool())) {
                    throw new IllegalArgumentException("toolMocks[" + i + "].tool is required.");
                }
                if (mock.response() == null) {
                    throw new IllegalArgumentException("toolMocks[" + i + "].response is required.");
                }
                Integer code = mock.response().httpCode();
                if (code != null && (code < 100 || code > 599)) {
                    throw new IllegalArgumentException("toolMocks[" + i + "].response.httpCode must be a valid HTTP status.");
                }
            }
        }

        List<TestCase> cases = suite.cases();
        if (cases == null) {
            return;
        }
        if (cases.size() > MAX_CASES) {
            throw new IllegalArgumentException("A suite may define at most " + MAX_CASES + " cases.");
        }
        var effective = suite.effectiveDefaults();
        long totalTurns = 0;
        for (int c = 0; c < cases.size(); c++) {
            TestCase testCase = cases.get(c);
            String where = "cases[" + c + "]";
            if (testCase == null) {
                throw new IllegalArgumentException(where + " is empty.");
            }
            validateRepetitions(testCase.repetitions(), where + ".repetitions");
            validatePassRate(testCase.passRate(), where + ".passRate");
            List<Turn> turns = testCase.turns();
            if (turns == null || turns.isEmpty()) {
                throw new IllegalArgumentException(where + ".turns needs at least one turn.");
            }
            if (turns.size() > MAX_TURNS_PER_CASE) {
                throw new IllegalArgumentException(where + " has " + turns.size() + " turns; at most " + MAX_TURNS_PER_CASE + " are allowed.");
            }
            for (int t = 0; t < turns.size(); t++) {
                Turn turn = turns.get(t);
                String turnWhere = where + ".turns[" + t + "]";
                if (turn == null || turn.input() == null) {
                    throw new IllegalArgumentException(turnWhere + ".input is required (use \"\" for an empty input).");
                }
                if (turn.input().length() > MAX_INPUT_CHARS) {
                    throw new IllegalArgumentException(turnWhere + ".input is longer than " + MAX_INPUT_CHARS + " characters.");
                }
                validateExpectation(turn.expect(), turnWhere + ".expect");
            }
            int repetitions = testCase.repetitions() != null ? testCase.repetitions() : effective.repetitions();
            totalTurns += (long) repetitions * turns.size();
        }
        if (totalTurns > MAX_TURNS_PER_RUN) {
            throw new IllegalArgumentException("This suite would run " + totalTurns + " turns (cases × repetitions × turns); at most "
                    + MAX_TURNS_PER_RUN + " are allowed per run. Split it into several suites.");
        }
    }

    private static void validateDefaults(AgentTestSuite.Defaults defaults) {
        if (defaults == null) {
            return;
        }
        if (defaults.toolMode() != null && !TOOL_MODES.contains(defaults.toolMode())) {
            throw new IllegalArgumentException("defaults.toolMode must be one of " + TOOL_MODES + ".");
        }
        if (defaults.unmockedTools() != null && !UNMOCKED_MODES.contains(defaults.unmockedTools())) {
            throw new IllegalArgumentException("defaults.unmockedTools must be one of " + UNMOCKED_MODES + ".");
        }
        validateRepetitions(defaults.repetitions(), "defaults.repetitions");
        validatePassRate(defaults.passRate(), "defaults.passRate");
        Integer timeout = defaults.turnTimeoutSeconds();
        if (timeout != null && (timeout < 1 || timeout > MAX_TURN_TIMEOUT_SECONDS)) {
            throw new IllegalArgumentException("defaults.turnTimeoutSeconds must be between 1 and " + MAX_TURN_TIMEOUT_SECONDS + ".");
        }
    }

    private static void validateRepetitions(Integer repetitions, String where) {
        if (repetitions != null && (repetitions < 1 || repetitions > MAX_REPETITIONS)) {
            throw new IllegalArgumentException(where + " must be between 1 and " + MAX_REPETITIONS + ".");
        }
    }

    private static void validatePassRate(Double passRate, String where) {
        if (passRate != null && (passRate.isNaN() || passRate < 0 || passRate > 1)) {
            throw new IllegalArgumentException(where + " must be between 0 and 1.");
        }
    }

    private static void validateExpectation(Expectation expect, String where) {
        if (expect == null) {
            return;
        }
        validatePattern(expect.outputMatches(), where + ".outputMatches");
        if (expect.properties() != null) {
            for (var entry : expect.properties().entrySet()) {
                if (isBlank(entry.getKey())) {
                    throw new IllegalArgumentException(where + ".properties has a blank property name.");
                }
                PropertyExpectation condition = entry.getValue();
                if (condition == null) {
                    throw new IllegalArgumentException(where + ".properties." + entry.getKey() + " has no condition.");
                }
                if (condition.equalTo() == null && condition.matches() == null && condition.exists() == null && condition.absent() == null) {
                    throw new IllegalArgumentException(where + ".properties." + entry.getKey()
                            + " needs one of equals, matches, exists, absent.");
                }
                validatePattern(condition.matches(), where + ".properties." + entry.getKey() + ".matches");
            }
        }
        if (expect.toolCalled() != null) {
            for (int i = 0; i < expect.toolCalled().size(); i++) {
                var call = expect.toolCalled().get(i);
                if (call == null || isBlank(call.tool())) {
                    throw new IllegalArgumentException(where + ".toolCalled[" + i + "].tool is required.");
                }
            }
        }
        if (expect.maxToolCalls() != null && expect.maxToolCalls() < 0) {
            throw new IllegalArgumentException(where + ".maxToolCalls cannot be negative.");
        }
        if (expect.maxCostUsd() != null && expect.maxCostUsd() < 0) {
            throw new IllegalArgumentException(where + ".maxCostUsd cannot be negative.");
        }
        if (expect.conversationState() != null && !CONVERSATION_STATES.contains(expect.conversationState())) {
            throw new IllegalArgumentException(where + ".conversationState must be one of " + CONVERSATION_STATES + ".");
        }
    }

    private static void validatePattern(String regex, String where) {
        if (regex == null) {
            return;
        }
        if (regex.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException(where + " is longer than " + MAX_PATTERN_LENGTH + " characters.");
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(where + " is not a valid regular expression: " + e.getDescription());
        }
        BoundedRegex.excessiveRepetition(regex).ifPresent(reason -> {
            throw new IllegalArgumentException(where + " " + reason + ".");
        });
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
