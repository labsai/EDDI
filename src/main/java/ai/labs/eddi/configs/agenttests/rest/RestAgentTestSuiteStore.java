/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agenttests.rest;

import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.IRestAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST implementation for agent test suite CRUD.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class RestAgentTestSuiteStore implements IRestAgentTestSuiteStore {
    private final IAgentTestSuiteStore suiteStore;
    private final RestVersionInfo<AgentTestSuite> restVersionInfo;

    @Inject
    public RestAgentTestSuiteStore(IAgentTestSuiteStore suiteStore, IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard) {
        this.suiteStore = suiteStore;
        this.restVersionInfo = new RestVersionInfo<>(resourceURI, suiteStore, documentDescriptorStore, resourceAccessGuard);
    }

    @Override
    public List<DocumentDescriptor> readTestSuiteDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public AgentTestSuite readTestSuite(String id, Integer version) {
        // The shared read: view access, null/negative checks, and version 0 meaning
        // "current" exactly as update and delete already treat it.
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateTestSuite(String id, Integer version, AgentTestSuite suite) {
        return restVersionInfo.update(id, version, suite);
    }

    @Override
    public Response createTestSuite(AgentTestSuite suite) {
        return restVersionInfo.create(suite);
    }

    @Override
    public Response deleteTestSuite(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public void requireCurrentVersionAccess(String id) {
        restVersionInfo.requireViewAccess(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return suiteStore.getCurrentResourceId(id);
    }
}
