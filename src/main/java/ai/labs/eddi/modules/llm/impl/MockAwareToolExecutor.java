/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.agenttests.ToolMockRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * A tool executor whose own handling of a response has effects beyond the tool
 * result, so an agent-test mock must go <em>through</em> it rather than around
 * it.
 * <p>
 * An httpcall tool is the case: {@code ApiCallExecutor} stores the response
 * ({@code saveResponse}), retries, and runs the call's {@code postResponse}
 * property instructions. Answering such a call with the mock directly — as
 * {@link ToolLoopRunner} does for every other tool — would skip all of that, so
 * an agent that sets a property from a tool's response would behave differently
 * mocked than live, and a {@code properties} expectation on it would fail.
 * {@link ToolLoopRunner} still decides and records the call through
 * {@link ToolMockRegistry#decideToolCall}; only a {@code MOCKED} decision is
 * handed here, and refusals never reach an executor.
 *
 * @since 6.6.0
 */
public interface MockAwareToolExecutor extends ToolExecutor {

    /**
     * Runs the call on {@code mock} in place of the outside call: no request is
     * built or sent, everything after the response happens as usual.
     *
     * @return the tool result for the model, in the same shape a live call returns
     */
    String executeMocked(ToolExecutionRequest request, ToolMockRegistry.MockResult mock);
}
