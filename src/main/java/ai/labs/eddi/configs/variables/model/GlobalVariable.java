/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A global configuration variable — a simple key-value pair available
 * deployment-wide across all agents and conversations.
 * <p>
 * Global variables are resolved in two ways:
 * <ul>
 * <li><b>Template layer</b>: {@code {{vars.<key>}}} — in LlmTask system
 * prompts</li>
 * <li><b>Late-binding layer</b>: {@code ${vars:<key>}} — everywhere (LLM
 * params, httpCalls, MCP, A2A, embeddings, Slack, the {@code type} field)</li>
 * </ul>
 * <p>
 * Unlike vault secrets, global variables are <b>not encrypted</b> and are
 * <b>fully visible</b> in the UI and logs. Use the vault for sensitive values.
 *
 * @author ginccc
 * @since 6.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GlobalVariable(
        String key,
        String value,
        String description,
        Boolean exportable) {
    /**
     * Compact constructor — defaults {@code exportable} to {@code true} if not
     * specified.
     */
    public GlobalVariable {
        if (exportable == null) {
            exportable = true;
        }
    }

    /**
     * Convenience constructor for key-value only (no description, exportable=true).
     */
    public GlobalVariable(String key, String value) {
        this(key, value, null, true);
    }
}
