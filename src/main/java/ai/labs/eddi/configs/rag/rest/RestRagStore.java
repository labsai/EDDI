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
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.modules.ingestion.RagIngestionSchedules;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        // Before the body is judged: a caller without EDIT should be told that,
        // rather than being handed validation errors about a resource they may not
        // change — and the 400s would also confirm the resource exists.
        restVersionInfo.requireEditAccess(id);
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
        warnOnPlaintextSecrets(ragConfiguration);

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

    // Visible for testing
    /**
     * Parameter names, in {@code embeddingParameters} and {@code storeParameters},
     * that hold a credential.
     * <p>
     * A vector store's connection parameters are not only API keys: pgvector takes
     * a database {@code password}, Elasticsearch a {@code password} and an
     * {@code apiKey}, MongoDB Atlas a {@code connectionString} whose userinfo
     * carries both.
     */
    static final Set<String> SECRET_PARAMETER_NAMES = Set.of("apikey", "password", "connectionstring", "token", "accesstoken",
            "secretkey", "secretaccesskey", "clientsecret", "privatekey");

    /**
     * Warns — rather than rejects — when a knowledge base stores a credential in
     * plaintext.
     * <p>
     * The same trade-off {@code RestLlmStore} and
     * {@code RestChannelIntegrationStore} already made, and for the same reasons: a
     * plaintext value is returned verbatim by {@code GET /ragstore/rags/{id}} and
     * lands in exports, but whether a value is a secret is a guess from its key
     * name, and rejecting would break every existing knowledge base on its next
     * update — including on the vault-less instances EDDI ships as the default. A
     * log line naming the parameter is the honest amount of certainty.
     * <p>
     * RAG was the one credential-carrying store with no such warning, which is how
     * a plaintext key here could stay invisible while the equivalent in an LLM
     * config was flagged. The value itself is never logged.
     */
    private static void warnOnPlaintextSecrets(RagConfiguration config) {
        for (String parameter : plaintextSecretParameters(config)) {
            LOGGER.warnf("Knowledge base '%s' stores %s in plaintext — it is returned verbatim by "
                    + "GET /ragstore/rags/{id} and included in exports. Store it in the secrets vault and "
                    + "reference it as ${vault:<key>} instead; it is resolved at retrieval time.",
                    LogSanitizer.sanitize(config.getName()), LogSanitizer.sanitize(parameter));
        }
    }

    // Visible for testing
    /**
     * The credential parameters of {@code config} whose value is a literal rather
     * than a reference ({@code ${vault:…}}, {@code ${connection:…}}) or a template
     * ({@code {properties.x}}).
     */
    static List<String> plaintextSecretParameters(RagConfiguration config) {
        List<String> found = new ArrayList<>();
        if (config == null) {
            return found;
        }
        collectPlaintextSecrets(config.getEmbeddingParameters(), "embeddingParameters", found);
        collectPlaintextSecrets(config.getStoreParameters(), "storeParameters", found);
        return found;
    }

    private static void collectPlaintextSecrets(Map<String, String> parameters, String mapName, List<String> sink) {
        if (parameters == null) {
            return;
        }
        for (var parameter : parameters.entrySet()) {
            String name = parameter.getKey();
            String value = parameter.getValue();
            // A "{" means a reference or template — ${vault:…}, ${connection:…},
            // {properties.x} — resolved at runtime rather than a literal secret.
            // Deliberately the same test RestLlmStore applies: two different
            // answers to "is this a literal?" would be a bug waiting to happen.
            if (name != null && SECRET_PARAMETER_NAMES.contains(name.toLowerCase(Locale.ROOT)) && value != null && !value.isBlank()
                    && !value.contains("{")) {
                sink.add(mapName + "." + name);
            }
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
     * <p>
     * The rule itself lives in {@link RagIngestionSchedules#requireValidCrons},
     * because this is not the only way a knowledge base is written: importing a ZIP
     * creates one through the store directly, and a second copy of the check would
     * have been a second chance to forget it.
     */
    private void requireValidCronExpressions(RagConfiguration ragConfiguration) {
        RagIngestionSchedules.requireValidCrons(ragConfiguration);
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
