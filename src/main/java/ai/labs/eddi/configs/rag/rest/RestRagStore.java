/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

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
    private final RagSourceIngestionService sourceIngestionService;

    @Inject
    public RestRagStore(IRagStore ragStore, IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator,
            ResourceAccessGuard resourceAccessGuard, RagSourceIngestionService sourceIngestionService) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, ragStore, documentDescriptorStore, resourceAccessGuard);
        this.ragStore = ragStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.sourceIngestionService = sourceIngestionService;
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
        // Read before writing: a source removed from sources[] must lose its
        // schedule, and afterwards there is nothing left to say which ones existed.
        RagConfiguration previous = readQuietly(id, version);
        Set<String> previousSourceIds = sourceIdsOf(previous);
        Response response = restVersionInfo.update(id, version, ragConfiguration);
        forgetIngestionStateOnRename(id, previous, ragConfiguration);
        syncIngestionSchedules(response, id, ragConfiguration, previousSourceIds);
        return response;
    }

    /**
     * Forgets what each source has ingested when the knowledge base is renamed.
     *
     * <p>
     * The vector store is addressed by the knowledge base's <em>name</em> while
     * ingestion state is keyed by its id, so a rename moves retrieval to a new,
     * empty namespace while every document still looks "unchanged" — runs keep
     * reporting success and the agent retrieves nothing, for ever. Clearing the
     * state makes the next run repopulate the new namespace.
     *
     * <p>
     * The chunks under the old name are left where they are: they are no longer
     * reachable through this configuration, and deleting data on a rename is worse
     * than leaving it. Purge the old knowledge base if it is not wanted.
     */
    private void forgetIngestionStateOnRename(String id, RagConfiguration previous, RagConfiguration updated) {
        if (previous == null || updated == null || previous.getName() == null
                || previous.getName().equals(updated.getName())) {
            return;
        }
        if (updated.getSources() == null || updated.getSources().isEmpty()) {
            return;
        }
        LOGGER.warnf("Knowledge base %s was renamed from '%s' to '%s'. Its vector store is addressed by name, so "
                + "ingestion state is being cleared and the next run of each source will re-ingest into the new "
                + "store. Chunks stored under the old name are left untouched.",
                LogSanitizer.sanitize(id), LogSanitizer.sanitize(previous.getName()),
                LogSanitizer.sanitize(updated.getName()));
        for (var source : updated.getSources()) {
            try {
                sourceIngestionService.purge(id, source);
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Could not clear ingestion state after renaming knowledge base %s; its sources "
                        + "will report every document as unchanged until they are purged by hand",
                        LogSanitizer.sanitize(id));
            }
        }
    }

    @Override
    public Response createRag(RagConfiguration ragConfiguration) {
        prepareForWrite(ragConfiguration);
        Response response = restVersionInfo.create(ragConfiguration);
        syncIngestionSchedules(response, null, ragConfiguration, Set.of());
        return response;
    }

    /**
     * Whether this knowledge base still has a current version after a delete.
     * Schedules belong to the knowledge base rather than to one version, so they
     * only go once nothing is left to crawl for.
     *
     * <p>
     * One lookup. {@code getCurrentResourceId} throws once the current row is
     * soft-deleted and succeeds while one exists, which is exactly this question.
     * An earlier version probed every version number up to the one in the request —
     * a loop sized by user input, so {@code ?version=2000000000} asked the server
     * for two billion reads (CodeQL flagged the arithmetic; the loop was the real
     * problem).
     */
    private boolean hasCurrentVersion(String id) {
        try {
            return restVersionInfo.getCurrentResourceId(id) != null;
        } catch (IResourceStore.ResourceNotFoundException e) {
            return false;
        }
    }

    private RagConfiguration readQuietly(String id, Integer version) {
        try {
            return restVersionInfo.read(id, version);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Set<String> sourceIdsOf(RagConfiguration configuration) {
        if (configuration == null || configuration.getSources() == null) {
            return Set.of();
        }
        return configuration.getSources().stream()
                .map(RagSourceIngestionService::sourceIdOf)
                .collect(Collectors.toSet());
    }

    /**
     * Makes the stored ingestion schedules match what was just written.
     *
     * <p>
     * A failure here is logged loudly rather than thrown: the knowledge base itself
     * saved correctly, and refusing the write would be worse. But it must be
     * visible — the draft this replaces swallowed the same failure and returned 201
     * for a source that looked scheduled and never ran.
     */
    private void syncIngestionSchedules(Response response, String knownId, RagConfiguration ragConfiguration,
                                        Set<String> previousSourceIds) {
        if (ragConfiguration == null
                || ((ragConfiguration.getSources() == null || ragConfiguration.getSources().isEmpty())
                        && previousSourceIds.isEmpty())) {
            // Nothing to add and nothing that used to exist — no schedule work.
            return;
        }
        IResourceId resourceId = resourceIdOf(response);
        String id = knownId != null ? knownId : resourceId == null ? null : resourceId.getId();
        if (id == null) {
            LOGGER.errorf("Could not determine the id of the knowledge base just written, so its ingestion "
                    + "schedules were NOT synchronised. Sources with a cron will not run until it is saved again.");
            return;
        }
        Integer version = resourceId == null ? null : resourceId.getVersion();
        try {
            sourceIngestionService.syncSchedules(id, version, ragConfiguration, previousSourceIds);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Ingestion schedules for knowledge base %s were NOT synchronised. Sources with a "
                    + "cron will not run until it is saved again.", LogSanitizer.sanitize(id));
        }
    }

    private static IResourceId resourceIdOf(Response response) {
        if (response == null || response.getLocation() == null) {
            return null;
        }
        try {
            return RestUtilities.extractResourceId(response.getLocation());
        } catch (RuntimeException e) {
            return null;
        }
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
            // Before assignSourceIds, which would dereference a null entry and answer
            // a bad request with a 500.
            requireNoNullSources(ragConfiguration);
            assignSourceIds(ragConfiguration);
            ragConfiguration.validate();
            requireValidCronExpressions(ragConfiguration);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    /**
     * Gives every ingestion source a stable id.
     *
     * <p>
     * The id is what the run, preview, history and purge endpoints address, and
     * what ingestion state is keyed by — so a source that arrives without one (the
     * normal case when the Manager adds it) must get one that then never changes.
     * Falling back to the source's <em>name</em> would mean renaming a source
     * orphaned everything it had ingested.
     */
    /**
     * Strips a copy's inherited ingestion identity: new ids, and no schedule until
     * an operator asks for one.
     */
    private void detachIngestionSources(RagConfiguration ragConfiguration) {
        if (ragConfiguration == null || ragConfiguration.getSources() == null) {
            return;
        }
        for (var source : ragConfiguration.getSources()) {
            source.setId(UUID.randomUUID().toString());
            source.setCron(null);
        }
    }

    private void requireNoNullSources(RagConfiguration ragConfiguration) {
        if (ragConfiguration.getSources() == null) {
            return;
        }
        if (ragConfiguration.getSources().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("The sources list contains a null entry");
        }
    }

    /**
     * A cron the scheduler cannot parse is refused here, where the operator is
     * waiting for an answer. Stored, it becomes a schedule that simply never fires,
     * and the source looks scheduled on every screen that shows it.
     */
    private void requireValidCronExpressions(RagConfiguration ragConfiguration) {
        if (ragConfiguration.getSources() == null) {
            return;
        }
        for (var source : ragConfiguration.getSources()) {
            String cron = source.getCron();
            if (cron == null || cron.isBlank()) {
                continue;
            }
            try {
                CronParser.validate(cron);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Ingestion source '" + source.getName() + "' has an invalid cron: " + e.getMessage());
            }
        }
    }

    private void assignSourceIds(RagConfiguration ragConfiguration) {
        if (ragConfiguration.getSources() == null) {
            return;
        }
        for (var source : ragConfiguration.getSources()) {
            if (source.getId() == null || source.getId().isBlank()) {
                source.setId(UUID.randomUUID().toString());
            }
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
        // Read before deleting: afterwards there is nothing left to tell us which
        // schedules belonged to this knowledge base, and an orphaned schedule keeps
        // crawling a third-party site on behalf of a config that no longer exists.
        RagConfiguration deleted = readQuietly(id, version);
        Response response = restVersionInfo.delete(id, version, permanent);

        // Only once no readable version is left. Deleting an OLD version of a
        // knowledge base that is still deployed at a newer one used to remove the
        // live version's schedules, so it silently stopped crawling.
        if (deleted != null && !hasCurrentVersion(id)) {
            try {
                sourceIngestionService.removeSchedules(id, deleted);
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Could not remove ingestion schedules for knowledge base %s",
                        LogSanitizer.sanitize(id));
            }
        }
        return response;
    }

    @Override
    public Response duplicateRag(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        RagConfiguration config = restVersionInfo.read(id, version);
        // Normalize only — see normalizeLegacyChunkStrategy: a copy of an existing
        // document must not be refused just because the rules tightened after it was
        // stored.
        normalizeLegacyChunkStrategy(config);
        // A copy must not inherit its original's ingestion identity. Vector stores are
        // keyed by the knowledge base NAME, which a duplicate shares, so a copied
        // source with the same id and cron would run against the original's documents
        // — replacing and tombstoning them while the original's state still says
        // "unchanged".
        detachIngestionSources(config);
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
