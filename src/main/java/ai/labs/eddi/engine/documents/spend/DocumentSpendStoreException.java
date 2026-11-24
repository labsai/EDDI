/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

/**
 * The document spend store could not answer. Admission treats it as a refusal
 * (fail closed); charging logs it and carries on, because the work already ran.
 */
public class DocumentSpendStoreException extends RuntimeException {

    public DocumentSpendStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
