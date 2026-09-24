/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

/**
 * A file that cannot be read: encrypted, corrupt, or not the format it claims.
 *
 * <p>
 * Carries a message written for whoever uploaded it, because that is who has to
 * do something about it — usually upload a different file.
 */
public class UnreadableDocumentException extends RuntimeException {

    public UnreadableDocumentException(String message) {
        super(message);
    }

    public UnreadableDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
