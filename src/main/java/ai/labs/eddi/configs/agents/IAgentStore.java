/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import java.util.List;

/**
 * Versioned store for agent configurations, the resources that tie workflows,
 * channels and capabilities together into a deployable agent. The runtime
 * orchestrators and the REST layer read agents through this interface; the
 * lookup below lists which agents reference a given workflow so callers can
 * warn about destructive changes before they happen.
 */
public interface IAgentStore extends IResourceStore<AgentConfiguration> {
    List<DocumentDescriptor> getAgentDescriptorsContainingWorkflow(String workflowId, Integer workflowVersion, boolean includePreviousVersions)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
