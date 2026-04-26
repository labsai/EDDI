/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class IllegalExtensionConfigurationException extends Exception {
    public IllegalExtensionConfigurationException(String message) {
        super(message);
    }

    public IllegalExtensionConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
