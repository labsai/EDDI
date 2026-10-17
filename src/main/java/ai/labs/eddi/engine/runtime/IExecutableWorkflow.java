/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.lifecycle.ILifecycleManager;

/**
 * @author ginccc
 */
public interface IExecutableWorkflow {
    String getName();

    String getDescription();

    String getWorkflowId();

    ILifecycleManager getLifecycleManager();

    /**
     * Whether a property-setter step of this workflow carries a
     * {@code scope: "secret"} instruction — the sign that the agent is designed to
     * receive secrets in a turn's input, which then must not be captured anywhere
     * outside the vault (dead letters included).
     */
    default boolean hasSecretPropertyInstructions() {
        return false;
    }
}
