/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for RAG (Knowledge Base) configuration store.
 */
@ApplicationScoped
public class RestRagStore implements IRestRagStore {

    private final IRagStore ragStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<RagConfiguration> restVersionInfo;

    @Inject
    public RestRagStore(IRagStore ragStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, ragStore, documentDescriptorStore);
        this.ragStore = ragStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(RagConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readRagDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.rag", filter, index, limit);
    }

    @Override
    public RagConfiguration readRag(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateRag(String id, Integer version, RagConfiguration ragConfiguration) {
        return restVersionInfo.update(id, version, ragConfiguration);
    }

    @Override
    public Response createRag(RagConfiguration ragConfiguration) {
        return restVersionInfo.create(ragConfiguration);
    }

    @Override
    public Response deleteRag(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateRag(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        RagConfiguration config = restVersionInfo.read(id, version);
        return restVersionInfo.create(config);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return ragStore.getCurrentResourceId(id);
    }
}
