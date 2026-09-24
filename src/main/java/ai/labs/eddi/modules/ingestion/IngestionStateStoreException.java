/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

/**
 * A {@link IIngestionStateStore} operation could not be completed.
 *
 * <p>
 * Unchecked, and deliberately never caught inside a store: ingestion state
 * decides what gets embedded, what is billed for, and what is deleted, so a
 * backend that answers "nothing found" when it means "the database is unwell"
 * silently re-embeds an entire knowledge base, or reports a false conflict, or
 * leaves a run marked RUNNING forever. Both backends fail the same way for the
 * same reason; the caller decides what to do about it.
 */
public class IngestionStateStoreException extends RuntimeException {

    public IngestionStateStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
