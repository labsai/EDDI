/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.impl.LlmCircuitBreakers.Failure;

/**
 * A turn was refused because the model's circuit is open and nothing else (no
 * further cascade step) could serve it. The message is EDDI-generated; the
 * failure class is the one that opened the circuit, so a caller (and
 * {@code onError: fallback}, which absorbs it like any other model-phase
 * failure) can tell this apart from a provider error.
 */
public class LlmCircuitOpenException extends LifecycleException {

    private final transient Failure failureClass;

    public LlmCircuitOpenException(String message, Failure failureClass) {
        super(message);
        this.failureClass = failureClass;
    }

    /** The class of failure that opened the circuit. */
    public Failure getFailureClass() {
        return failureClass;
    }
}
