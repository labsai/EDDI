/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class CannotExecuteException extends LifecycleException {
    public CannotExecuteException(String message) {
        super(message);
    }
}
