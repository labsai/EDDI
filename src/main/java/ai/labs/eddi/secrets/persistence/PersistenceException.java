/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.persistence;

/**
 * Unchecked exception thrown when a persistence operation fails. Wraps database
 * exceptions (SQLException, MongoException, etc.) so callers can handle
 * persistence failures without importing database-specific types.
 *
 * @author ginccc
 * @since 6.0.0
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
