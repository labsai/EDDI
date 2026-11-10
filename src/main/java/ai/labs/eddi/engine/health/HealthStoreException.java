/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

/** The Agent Health store could not do what it was asked. */
public class HealthStoreException extends RuntimeException {

    public HealthStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
