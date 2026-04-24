/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class UnrecognizedExtensionException extends Exception {
    public UnrecognizedExtensionException(String message) {
        super(message);
    }

    public UnrecognizedExtensionException(String message, Throwable cause) {
        super(message, cause);
    }
}
