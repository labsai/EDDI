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
    /**
     * {@link #update(String, Integer, Object)}, declaring whether the new version
     * is compatible with the one it replaces — see
     * {@link AgentConfiguration#getCompatibilityGeneration()}. The plain update is
     * this with {@code compatible = false}: every new version is a breaking change
     * unless the caller says otherwise.
     */
    Integer update(String id, Integer version, AgentConfiguration agentConfiguration, boolean compatible)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException;

    List<DocumentDescriptor> getAgentDescriptorsContainingWorkflow(String workflowId, Integer workflowVersion, boolean includePreviousVersions)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;
}
