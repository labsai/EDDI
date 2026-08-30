/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for RAG (Knowledge Base) configuration store.
 */
@ApplicationScoped
public class RestRagStore implements IRestRagStore {

    private static final Logger LOGGER = Logger.getLogger(RestRagStore.class);

    private final IRagStore ragStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<RagConfiguration> restVersionInfo;

    @Inject
    public RestRagStore(IRagStore ragStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator,
            ResourceAccessGuard resourceAccessGuard) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, ragStore, documentDescriptorStore, resourceAccessGuard);
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
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public RagConfiguration readRag(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateRag(String id, Integer version, RagConfiguration ragConfiguration) {
        prepareForWrite(ragConfiguration);
        return restVersionInfo.update(id, version, ragConfiguration);
    }

    @Override
    public Response createRag(RagConfiguration ragConfiguration) {
        prepareForWrite(ragConfiguration);
        return restVersionInfo.create(ragConfiguration);
    }

    /**
     * Enforces at the write boundary what the engine can actually honour, so an
     * unusable knowledge base can never be persisted in the first place.
     * <p>
     * Historically documented but never implemented chunk strategies are rewritten
     * to the behavior ingestion always applied, which keeps knowledge bases created
     * against the old documentation updatable and importable (this method is also
     * on the import path via {@code RestImportService.updateRag}). Anything else
     * the engine cannot implement is rejected with an actionable 400 instead of
     * being accepted and silently ignored.
     * <p>
     * Retrieval deliberately does <em>not</em> enforce this — see
     * {@link RagConfiguration#findUnsupportedSettings()}.
     */
    private void prepareForWrite(RagConfiguration ragConfiguration) {
        if (ragConfiguration == null) {
            // RestVersionInfo rejects null with its own error message.
            return;
        }

        normalizeLegacyChunkStrategy(ragConfiguration);

        try {
            ragConfiguration.validate();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    /**
     * The half of {@link #prepareForWrite} that is always safe to apply: rewrite a
     * legacy strategy to what ingestion actually did, and say so.
     * <p>
     * Duplication uses only this half. Rejecting there would mean the store happily
     * serves a document through {@code readRag} that it then refuses to copy — a
     * new failure mode for data that already exists, rather than a guard against
     * creating bad data. Every other {@code duplicate*} endpoint declines to
     * validate for the same reason.
     */
    private void normalizeLegacyChunkStrategy(RagConfiguration ragConfiguration) {
        if (ragConfiguration == null) {
            return;
        }

        String normalized = ragConfiguration.normalizeLegacyChunkStrategy();
        if (normalized != null) {
            LOGGER.warnf("Knowledge base '%s': %s", LogSanitizer.sanitize(ragConfiguration.getName()), LogSanitizer.sanitize(normalized));
        }
    }

    @Override
    public Response deleteRag(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateRag(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        RagConfiguration config = restVersionInfo.read(id, version);
        // Normalize only — see normalizeLegacyChunkStrategy: a copy of an existing
        // document must not be refused just because the rules tightened after it was
        // stored.
        normalizeLegacyChunkStrategy(config);
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
