/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * A tool executor that can tell, before anything runs, whether a call's
 * arguments are acceptable.
 * <p>
 * {@code ToolLoopRunner} asks every executor implementing this before the
 * approval gate classifies the batch: a call with violations is answered with a
 * {@code NOT_EXECUTED} result listing them and is never shown to the gate, so
 * no human is asked to approve a call that cannot be right. Today the httpcall
 * tools implement it from {@code ApiCall.argumentSchema}; an executor that does
 * not implement it is never refused here. Kept to a single method so it can be
 * fronted by a configurable tool-call guardrail later without changing callers.
 */
public interface ToolArgumentValidator {

    /**
     * @return one human- and model-readable line per violation; empty when the call
     *         may proceed. Never null.
     */
    List<String> argumentViolations(ToolExecutionRequest request);
}
