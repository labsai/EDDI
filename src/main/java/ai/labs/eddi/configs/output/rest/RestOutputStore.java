/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.output.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import jakarta.ws.rs.core.Response;
import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestOutputStore implements IRestOutputStore {
    private final IOutputStore outputStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<OutputConfigurationSet> restVersionInfo;

    @Inject
    public RestOutputStore(IOutputStore outputStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, outputStore, documentDescriptorStore);
        this.outputStore = outputStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(OutputConfigurationSet.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readOutputDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.output", filter, index, limit);
    }

    @Override
    public OutputConfigurationSet readOutputSet(String id, Integer version, String filter, String order, Integer index, Integer limit) {
        try {
            return outputStore.read(id, version, filter, order, index, limit);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<String> readOutputKeys(String id, Integer version, String filter, Integer limit) {
        try {
            return outputStore.readActions(id, version, filter, limit);
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response updateOutputSet(String id, Integer version, OutputConfigurationSet outputConfigurationSet) {
        return restVersionInfo.update(id, version, outputConfigurationSet);
    }

    @Override
    public Response createOutputSet(OutputConfigurationSet outputConfigurationSet) {
        return restVersionInfo.create(outputConfigurationSet);
    }

    @Override
    public Response deleteOutputSet(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response patchOutputSet(String id, Integer version, List<PatchInstruction<OutputConfigurationSet>> patchInstructions) {
        try {
            OutputConfigurationSet currentOutputConfigurationSet = outputStore.read(id, version);
            OutputConfigurationSet patchedOutputConfigurationSet = patchDocument(currentOutputConfigurationSet, patchInstructions);

            return updateOutputSet(id, version, patchedOutputConfigurationSet);

        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    private OutputConfigurationSet patchDocument(OutputConfigurationSet currentOutputConfigurationSet,
                                                 List<PatchInstruction<OutputConfigurationSet>> patchInstructions)
            throws IResourceStore.ResourceStoreException {

        for (var patchInstruction : patchInstructions) {
            var outputConfigurationSetPatch = patchInstruction.getDocument();
            switch (patchInstruction.getOperation()) {
                case SET -> {
                    currentOutputConfigurationSet.getOutputSet().removeAll(outputConfigurationSetPatch.getOutputSet());
                    currentOutputConfigurationSet.getOutputSet().addAll(outputConfigurationSetPatch.getOutputSet());
                }
                case DELETE -> currentOutputConfigurationSet.getOutputSet().removeAll(outputConfigurationSetPatch.getOutputSet());
                default -> throw new IResourceStore.ResourceStoreException("Patch operation must be either SET or DELETE!");
            }
        }

        return currentOutputConfigurationSet;
    }

    @Override
    public Response duplicateOutputSet(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        try {
            var outputConfigurationSet = outputStore.read(id, version);
            return restVersionInfo.create(outputConfigurationSet);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return outputStore.getCurrentResourceId(id);
    }
}
