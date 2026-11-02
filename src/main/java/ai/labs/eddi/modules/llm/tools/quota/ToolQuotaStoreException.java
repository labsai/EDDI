/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

/**
 * The counter store could not answer. Callers fail closed: the tool call is not
 * run.
 */
public class ToolQuotaStoreException extends RuntimeException {

    public ToolQuotaStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
