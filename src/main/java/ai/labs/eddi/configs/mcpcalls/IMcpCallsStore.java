/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls;

import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Store interface for MCP Calls configurations.
 */
public interface IMcpCallsStore extends IResourceStore<McpCallsConfiguration> {

    /**
     * Reads all actions defined across McpCall entries in a given configuration
     * version.
     *
     * @param id
     *            resource ID
     * @param version
     *            resource version
     * @param filter
     *            optional filter string
     * @param limit
     *            max results (0 = all)
     * @return list of action strings
     */
    List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
