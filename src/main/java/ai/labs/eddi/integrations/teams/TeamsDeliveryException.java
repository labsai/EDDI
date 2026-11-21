/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

/**
 * A message could not be delivered to Teams. The message never contains a
 * credential.
 *
 * @since 6.7.0
 */
public class TeamsDeliveryException extends RuntimeException {

    public TeamsDeliveryException(String message) {
        super(message);
    }

    public TeamsDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
