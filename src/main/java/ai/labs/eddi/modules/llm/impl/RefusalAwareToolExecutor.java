/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.agenttests.ToolMockRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * A tool executor that can refuse a call before it does anything — and says so
 * explicitly, not through the wording of its answer.
 * <p>
 * An httpcall tool refuses arguments it cannot place in its request (a schema
 * violation, a body that would not be JSON, an argument the
 * {@code ToolArgumentBodyEscaper} guard refuses): nothing is sent, and the
 * answer to the model is an error. {@link ToolLoopRunner} reads
 * {@link Outcome#refused()} to count such a call as not executed, so an
 * observer of executed calls (the sampled audit) never records it as one.
 */
interface RefusalAwareToolExecutor {

    /**
     * One call's answer.
     *
     * @param result
     *            the text the model receives
     * @param refused
     *            true when the call was refused before anything ran
     */
    record Outcome(String result, boolean refused) {

        static Outcome ran(String result) {
            return new Outcome(result, false);
        }

        static Outcome refused(String result) {
            return new Outcome(result, true);
        }
    }

    /**
     * Runs the call, on {@code mock} when one is given (as
     * {@link MockAwareToolExecutor#executeMocked}), otherwise live.
     */
    Outcome executeJudged(ToolExecutionRequest request, ToolMockRegistry.MockResult mock);
}
