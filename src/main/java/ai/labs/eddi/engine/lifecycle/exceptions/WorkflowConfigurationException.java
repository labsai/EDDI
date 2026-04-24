/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class WorkflowConfigurationException extends Exception {
    public WorkflowConfigurationException(String message) {
        super(message);
    }

    public WorkflowConfigurationException(String message, Exception e) {
        super(message, e);
    }
}
