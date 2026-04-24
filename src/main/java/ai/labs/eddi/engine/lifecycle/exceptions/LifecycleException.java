/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class LifecycleException extends Exception {
    public LifecycleException(String message) {
        super(message);
    }

    public LifecycleException(String message, Exception exception) {
        super(message, exception);
    }

    public static class LifecycleInterruptedException extends LifecycleException {

        public LifecycleInterruptedException(String message) {
            super(message);
        }

        public LifecycleInterruptedException(String message, Exception e) {
            super(message);
        }
    }
}
