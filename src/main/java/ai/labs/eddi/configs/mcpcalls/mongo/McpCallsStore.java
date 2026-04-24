/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.mongo;

import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCall;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MongoDB-backed store for MCP Calls configurations.
 */
@ApplicationScoped
public class McpCallsStore extends AbstractResourceStore<McpCallsConfiguration> implements IMcpCallsStore {

    @Inject
    public McpCallsStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "mcpcalls", documentBuilder, McpCallsConfiguration.class);
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws ResourceNotFoundException, ResourceStoreException {

        McpCallsConfiguration config = read(id, version);
        if (config.getMcpCalls() == null) {
            return Collections.emptyList();
        }

        List<String> actions = config.getMcpCalls().stream().map(McpCall::getActions).filter(a -> a != null).flatMap(Collection::stream)
                .collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, Math.min(limit, actions.size())) : actions;
    }
}
