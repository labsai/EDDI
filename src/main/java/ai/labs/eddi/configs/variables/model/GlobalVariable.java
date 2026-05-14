/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A global configuration variable — a key-value pair scoped at the <b>tenant
 * level</b>, available across all agents within that tenant.
 * <p>
 * Global variables are resolved in two ways:
 * <ul>
 * <li><b>Template layer</b>: {@code {{vars.<key>}}} — in LlmTask system
 * prompts</li>
 * <li><b>Late-binding layer</b>: {@code ${vars:<key>}} (default tenant) or
 * {@code ${vars:tenantId/<key>}} (explicit tenant) — everywhere (LLM params,
 * httpCalls, MCP, A2A, embeddings, Slack, the {@code type} field)</li>
 * </ul>
 * <p>
 * Unlike vault secrets, global variables are <b>not encrypted</b> and are
 * <b>fully visible</b> in the UI and logs. Use the vault for sensitive values.
 * <p>
 * Follows the same tenant scoping model as
 * {@link ai.labs.eddi.secrets.model.SecretReference}: single-tenant deployments
 * use {@code "default"} implicitly; multi-tenant deployments pass an explicit
 * tenant ID.
 *
 * @author ginccc
 * @since 6.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GlobalVariable(
        String tenantId,
        String key,
        String value,
        String description,
        Boolean exportable) {

    /** Default tenant ID used when none is specified. */
    public static final String DEFAULT_TENANT = "default";

    /**
     * Compact constructor — defaults {@code tenantId} to {@code "default"} and
     * {@code exportable} to {@code true} if not specified.
     */
    public GlobalVariable {
        if (tenantId == null) {
            tenantId = DEFAULT_TENANT;
        }
        if (exportable == null) {
            exportable = true;
        }
    }

    /**
     * Convenience constructor for key-value only (default tenant, no description,
     * exportable=true).
     */
    public GlobalVariable(String key, String value) {
        this(DEFAULT_TENANT, key, value, null, true);
    }

    /**
     * Convenience constructor with tenant + key-value (no description,
     * exportable=true).
     */
    public GlobalVariable(String tenantId, String key, String value) {
        this(tenantId, key, value, null, true);
    }
}
