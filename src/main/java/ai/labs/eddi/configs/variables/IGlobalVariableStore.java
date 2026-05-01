/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables;

import ai.labs.eddi.configs.variables.model.GlobalVariable;

import java.util.List;
import java.util.Map;

/**
 * Persistence interface for global configuration variables.
 * <p>
 * Unlike {@code IResourceStore}, global variables are <b>not versioned</b> —
 * they are simple key-value pairs for operational deployment configuration, not
 * agent definitions.
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface IGlobalVariableStore {

    /**
     * Get all variables as a flat key→value map (used by the resolver cache).
     */
    Map<String, String> getAll();

    /**
     * Get a single variable by key, or {@code null} if not found.
     */
    GlobalVariable get(String key);

    /**
     * Create or update a variable. If a variable with the same key exists, it is
     * overwritten.
     */
    void upsert(GlobalVariable variable);

    /**
     * Delete a variable by key. No-op if the key does not exist.
     */
    void delete(String key);

    /**
     * List all variables as full objects (with descriptions).
     */
    List<GlobalVariable> listAll();
}
