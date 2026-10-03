/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.ToolFailureException;

import java.util.function.Supplier;

/**
 * What the model would see for a built-in tool call: the returned result, or —
 * when the tool reports a transient failure by throwing
 * {@link ToolFailureException} — the failure's model-facing text, exactly as
 * {@code ToolExecutionService} hands it on.
 */
final class ToolTestResults {

    private ToolTestResults() {
    }

    static String textOf(Supplier<String> call) {
        try {
            return call.get();
        } catch (ToolFailureException failure) {
            return failure.getMessage();
        }
    }
}
