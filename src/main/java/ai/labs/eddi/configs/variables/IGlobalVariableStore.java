/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables;

import ai.labs.eddi.configs.variables.model.GlobalVariable;

import java.util.List;
import java.util.Map;

/**
 * Persistence interface for global configuration variables, scoped per tenant.
 * <p>
 * Unlike {@code IResourceStore}, global variables are <b>not versioned</b> —
 * they are simple key-value pairs for operational deployment configuration, not
 * agent definitions. Each variable is uniquely identified by
 * {@code (tenantId, key)}.
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface IGlobalVariableStore {

    /**
     * Get all variables for a tenant as a flat key→value map (used by the resolver
     * cache).
     */
    Map<String, String> getAll(String tenantId);

    /**
     * Get a single variable by tenant and key, or {@code null} if not found.
     */
    GlobalVariable get(String tenantId, String key);

    /**
     * Create or update a variable. If a variable with the same tenant+key exists,
     * it is overwritten. The {@code tenantId} is taken from the record.
     */
    void upsert(GlobalVariable variable);

    /**
     * Delete a variable by tenant and key. No-op if the key does not exist.
     */
    void delete(String tenantId, String key);

    /**
     * List all variables for a tenant as full objects (with descriptions).
     */
    List<GlobalVariable> listAll(String tenantId);
}
