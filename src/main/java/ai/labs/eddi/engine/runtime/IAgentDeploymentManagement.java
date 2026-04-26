/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

/**
 * @author ginccc
 */
public interface IAgentDeploymentManagement {
    void autoDeployAgents() throws AutoDeploymentException;

    class AutoDeploymentException extends Exception {
        public AutoDeploymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
