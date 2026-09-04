/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IRestImportService;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.backup.model.SyncRequest;
import ai.labs.eddi.backup.model.UpgradeResult;
import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.FileUtilities;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import io.quarkus.runtime.LaunchMode;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.createDocumentDescriptor;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestImportService extends AbstractBackupService implements IRestImportService {
    private static final Pattern EDDI_URI_PATTERN = Pattern.compile("\"eddi://ai.labs..*?\"");
    private static final String AGENT_FILE_ENDING = ".agent.json";
    /** EDDI 5.x named the agent file {@code <id>.bot.json}. */
    private static final String LEGACY_AGENT_FILE_ENDING = ".bot.json";
    private static final List<String> AGENT_FILE_ENDINGS = List.of(AGENT_FILE_ENDING, LEGACY_AGENT_FILE_ENDING);
    private static final String DESCRIPTOR_FILE_ENDING = ".descriptor.json";
    private static final String STRATEGY_CREATE = "create";
    private static final String STRATEGY_MERGE = "merge";
    private static final String STRATEGY_UPGRADE = "upgrade";
    private static final Set<String> SUPPORTED_STRATEGIES = Set.of(STRATEGY_CREATE, STRATEGY_MERGE, STRATEGY_UPGRADE);

    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp", "import"));
    private final IZipArchive zipArchive;
    private final IJsonSerialization jsonSerialization;

    private final IMigrationManager migrationManager;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final TemplateSyntaxMigrator templateSyntaxMigrator;
    private final StructuralMatcher structuralMatcher;
    private final UpgradeExecutor upgradeExecutor;
    private final IScheduleStore scheduleStore;
    private final BackupMetrics metrics;

    private final ResourceAccessGuard resourceAccessGuard;

    private static final Logger LOGGER = Logger.getLogger(RestImportService.class);

    @Inject
    public RestImportService(IZipArchive zipArchive, IJsonSerialization jsonSerialization,
            IMigrationManager migrationManager,
            IDocumentDescriptorStore documentDescriptorStore, TemplateSyntaxMigrator templateSyntaxMigrator,
            StructuralMatcher structuralMatcher, UpgradeExecutor upgradeExecutor, IScheduleStore scheduleStore,
            BackupMetrics metrics, ResourceAccessGuard resourceAccessGuard) {
        this.metrics = metrics;
        this.resourceAccessGuard = resourceAccessGuard;
        this.zipArchive = zipArchive;
        this.jsonSerialization = jsonSerialization;
        this.migrationManager = migrationManager;
        this.documentDescriptorStore = documentDescriptorStore;
        this.templateSyntaxMigrator = templateSyntaxMigrator;
        this.structuralMatcher = structuralMatcher;
        this.upgradeExecutor = upgradeExecutor;
        this.scheduleStore = scheduleStore;
    }

    // ==================== Preview ====================

    @Override
    public ImportPreview previewImport(InputStream zippedAgentConfigFiles, String targetAgentId) {
        // When targetAgentId is provided, use the new structural matching pipeline
        if (targetAgentId != null && !targetAgentId.isBlank()) {
            return previewUpgrade(zippedAgentConfigFiles, targetAgentId);
        }
        // Legacy merge preview path
        File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
        try {
            this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);
            var targetDirPath = targetDir.getPath();

            for (Path agentFilePath : singleAgentFileIn(Paths.get(targetDirPath))) {
                String agentFileString = readFile(agentFilePath);
                String agentOriginId = extractIdFromAgentFilename(agentFilePath);
                String agentName = readNameFromDescriptor(Paths.get(targetDirPath), agentOriginId);

                List<ResourceDiff> diffs = new ArrayList<>();

                // Agent itself
                diffs.add(buildResourceDiff(agentOriginId, "agent", agentName));

                // Workflows & their extensions
                AgentConfiguration agentConfig = jsonSerialization.deserialize(agentFileString, AgentConfiguration.class);
                for (URI workflowUri : agentConfig.getWorkflows()) {
                    IResourceId workflowResourceId = RestUtilities.extractResourceId(workflowUri);
                    if (workflowResourceId == null)
                        continue;

                    String workflowId = workflowResourceId.getId();
                    String workflowVersion = String.valueOf(workflowResourceId.getVersion());
                    String workflowName = readNameFromDescriptor(Paths.get(targetDirPath, workflowId, workflowVersion), workflowId);
                    diffs.add(buildResourceDiff(workflowId, "workflow", workflowName));

                    // Read workflow file to find extension URIs
                    var dir = Paths.get(FileUtilities.buildPath(targetDirPath, workflowId, workflowVersion));
                    try (var wfStream = Files.newDirectoryStream(dir,
                            p -> p.toString().endsWith(".workflow.json") || p.toString().endsWith(".package.json"))) {
                        for (Path workflowFilePath : wfStream) {
                            String workflowFileString = readFile(workflowFilePath);
                            // Normalize legacy URIs from v5 ZIPs
                            workflowFileString = normalizeLegacyUris(workflowFileString);
                            addExtensionDiffs(diffs, workflowFileString, dir);
                        }
                    }
                }
                // Snippets (global resources, not workflow-embedded)
                addSnippetDiffs(diffs, Paths.get(targetDirPath));
                // Scheduled triggers, which the import recreates for the new agent
                addScheduleDiffs(diffs, Paths.get(targetDirPath));

                return new ImportPreview(agentOriginId, agentName, null, null, diffs);
            }

            throw noAgentFileFound();
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Preview failed: " + e.getMessage(), e);
        } finally {
            deleteTempDirectoryQuietly(targetDir.toPath());
        }
    }

    private void addExtensionDiffs(List<ResourceDiff> diffs, String workflowFileString, Path workflowDir)
            throws CallbackMatcher.CallbackMatcherException {

        addDiffsForType(diffs, workflowFileString, DICTIONARY_URI_PATTERN, DICTIONARY_EXT, workflowDir);
        addDiffsForType(diffs, workflowFileString, BEHAVIOR_URI_PATTERN, BEHAVIOR_EXT, workflowDir);
        addDiffsForType(diffs, workflowFileString, HTTPCALLS_URI_PATTERN, HTTPCALLS_EXT, workflowDir);
        addDiffsForType(diffs, workflowFileString, LANGCHAIN_URI_PATTERN, LLM_EXT, workflowDir);
        addDiffsForType(diffs, workflowFileString, PROPERTY_URI_PATTERN, PROPERTY_EXT, workflowDir);
        addDiffsForType(diffs, workflowFileString, OUTPUT_URI_PATTERN, OUTPUT_EXT, workflowDir);
        addDiffsForType(diffs, workflowFileString, MCPCALLS_URI_PATTERN, MCPCALLS_EXT, workflowDir);
        addDiffsForType(diffs, workflowFileString, RAG_URI_PATTERN, RAG_EXT, workflowDir);
    }

    private void addDiffsForType(List<ResourceDiff> diffs, String workflowFileString, Pattern uriPattern, String ext, Path workflowDir)
            throws CallbackMatcher.CallbackMatcherException {

        List<URI> uris = extractResourcesUris(workflowFileString, uriPattern);
        for (URI uri : uris) {
            IResourceId resourceId = RestUtilities.extractResourceId(uri);
            if (resourceId == null)
                continue;
            String name = readNameFromDescriptor(workflowDir, resourceId.getId());
            diffs.add(buildResourceDiff(resourceId.getId(), ext, name));
        }
    }

    /**
     * Scans the snippets/ directory in the ZIP and adds preview diffs. Uses
     * name-based lookup to match existing snippets — consistent with the import
     * logic's name-based deduplication.
     */
    private void addSnippetDiffs(List<ResourceDiff> diffs, Path targetDirPath) {
        Path snippetsDir = findSnippetsDir(targetDirPath);
        if (snippetsDir == null || !Files.exists(snippetsDir)) {
            return;
        }

        try {
            // Build name→IResourceId map of existing snippets for accurate CREATE/UPDATE
            IRestPromptSnippetStore restSnippetStore = getRestResourceStore(IRestPromptSnippetStore.class);
            Map<String, IResourceId> existingByName = buildExistingSnippetNameMap(restSnippetStore);

            try (var snippetStream = Files.newDirectoryStream(snippetsDir,
                    p -> p.toString().endsWith("." + SNIPPET_EXT + ".json"))) {
                for (Path snippetFilePath : snippetStream) {
                    try {
                        String json = readFile(snippetFilePath);
                        PromptSnippet snippet = jsonSerialization.deserialize(json, PromptSnippet.class);
                        if (snippet == null || snippet.getName() == null)
                            continue;

                        String snippetName = snippet.getName();
                        IResourceId existing = existingByName.get(snippetName);
                        if (existing != null) {
                            diffs.add(new ResourceDiff(existing.getId(), SNIPPET_EXT, snippetName,
                                    DiffAction.UPDATE, existing.getId(), existing.getVersion(),
                                    "name", null, null, -1));
                        } else {
                            diffs.add(new ResourceDiff(null, SNIPPET_EXT, snippetName,
                                    DiffAction.CREATE, null, null,
                                    null, null, null, -1));
                        }
                    } catch (Exception e) {
                        LOGGER.debugf("Could not preview snippet %s: %s", snippetFilePath.getFileName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not scan snippets for preview: %s", e.getMessage());
        }
    }

    /**
     * Lists the archive's scheduled triggers. They are always CREATE: a schedule id
     * belongs to the store that issued it, so there is nothing on this instance to
     * match against, and the import repoints each one at the newly created agent.
     */
    private void addScheduleDiffs(List<ResourceDiff> diffs, Path targetDirPath) {
        Path schedulesDir = findArchiveDir(targetDirPath, SCHEDULES_DIR);
        if (schedulesDir == null) {
            return;
        }
        try (var scheduleStream = Files.newDirectoryStream(schedulesDir,
                p -> p.toString().endsWith("." + SCHEDULE_EXT + ".json"))) {
            for (Path scheduleFilePath : scheduleStream) {
                try {
                    ScheduleConfiguration schedule = jsonSerialization.deserialize(
                            readFile(scheduleFilePath), ScheduleConfiguration.class);
                    if (schedule == null) {
                        continue;
                    }
                    diffs.add(new ResourceDiff(schedule.getId(), SCHEDULE_EXT, schedule.getName(),
                            DiffAction.CREATE, null, null, null, null, null, -1));
                } catch (Exception e) {
                    LOGGER.debugf("Could not preview schedule %s: %s", scheduleFilePath.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not scan schedules for preview: %s", e.getMessage());
        }
    }

    private ResourceDiff buildResourceDiff(String originId, String resourceType, String name) {
        try {
            List<DocumentDescriptor> existing = documentDescriptorStore.findByOriginId(originId);
            if (!existing.isEmpty()) {
                DocumentDescriptor desc = existing.getFirst();
                IResourceId localResourceId = RestUtilities.extractResourceId(desc.getResource());
                if (localResourceId != null) {
                    return new ResourceDiff(originId, resourceType, name, DiffAction.UPDATE, localResourceId.getId(), localResourceId.getVersion(),
                            "originId", null, null, -1);
                }
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            LOGGER.debug("Could not look up origin ID " + originId + ": " + e.getMessage());
        }

        // Fallback: try by resource ID directly (handles export→re-import round-trip
        // where the ZIP's resource IDs match the local resource IDs, not an originId)
        try {
            IResourceId currentId = documentDescriptorStore.getCurrentResourceId(originId);
            if (currentId != null) {
                DocumentDescriptor desc = documentDescriptorStore.readDescriptor(currentId.getId(), currentId.getVersion());
                if (desc != null) {
                    IResourceId localResourceId = RestUtilities.extractResourceId(desc.getResource());
                    if (localResourceId != null) {
                        return new ResourceDiff(originId, resourceType, name, DiffAction.UPDATE, localResourceId.getId(),
                                localResourceId.getVersion(), "resourceId", null, null, -1);
                    }
                }
            }
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            LOGGER.debugf("Fallback resource ID lookup for '%s' not found: %s", originId, e.getMessage());
        }

        return new ResourceDiff(originId, resourceType, name, DiffAction.CREATE, null, null, null, null, null, -1);
    }

    private String readNameFromDescriptor(Path dir, String resourceId) {
        try {
            Path descriptorPath = Paths.get(dir.toString(), resourceId + DESCRIPTOR_FILE_ENDING);
            if (Files.exists(descriptorPath)) {
                String content = readFile(descriptorPath);
                DocumentDescriptor dd = jsonSerialization.deserialize(content, DocumentDescriptor.class);
                return dd.getName();
            }
        } catch (IOException e) {
            // ignore — name is optional
        }
        return null;
    }

    /**
     * Whether a file in the archive root is an agent config. EDDI 6 writes
     * {@code <id>.agent.json}; a genuine 5.x export writes {@code <id>.bot.json}
     * and is accepted too — the rest of the class already normalizes v5 URIs and
     * accepts the v5 {@code .package.json} workflow file, so refusing the agent
     * file alone made a v5 import a silent no-op that still answered 200.
     */
    private static boolean isAgentFile(Path path) {
        String filename = path.toString();
        return AGENT_FILE_ENDINGS.stream().anyMatch(filename::endsWith);
    }

    private static String agentFileEndingOf(Path path) {
        String filename = path.toString();
        return AGENT_FILE_ENDINGS.stream().filter(filename::endsWith).findFirst().orElse(AGENT_FILE_ENDING);
    }

    /**
     * The one agent file in the archive root, as a single-element list, or an empty
     * list when there is none.
     * <p>
     * More than one is rejected: the preview only ever described the first file it
     * enumerated while the import created every one of them, so an operator
     * approved an import of one agent and got several, with a Location header
     * pointing at whichever happened to be enumerated last.
     */
    private List<Path> singleAgentFileIn(Path archiveRoot) throws IOException {
        List<Path> agentFiles = new ArrayList<>();
        try (var directoryStream = Files.newDirectoryStream(archiveRoot, RestImportService::isAgentFile)) {
            directoryStream.forEach(agentFiles::add);
        }
        if (agentFiles.size() > 1) {
            throw new BadRequestException("The archive contains " + agentFiles.size()
                    + " agent configuration files. Import one agent per archive.");
        }
        return agentFiles;
    }

    private static BadRequestException noAgentFileFound() {
        return new BadRequestException("The archive contains no agent configuration file: expected one of "
                + String.join(", ", AGENT_FILE_ENDINGS) + " in the archive root.");
    }

    private String extractIdFromAgentFilename(Path agentFilePath) {
        String filename = agentFilePath.getFileName().toString();
        return filename.substring(0, filename.length() - agentFileEndingOf(agentFilePath).length());
    }

    // ==================== Import ====================

    @Override
    public Response importAgent(InputStream zippedAgentConfigFiles, String strategy, String selectedOriginIds,
                                String targetAgentId, String workflowOrder) {
        metrics.importAttempted();
        try {
            String requestedStrategy = isNullOrEmpty(strategy) ? STRATEGY_CREATE : strategy.toLowerCase(Locale.ROOT);
            if (!SUPPORTED_STRATEGIES.contains(requestedStrategy)) {
                throw new BadRequestException("Unknown strategy '" + strategy + "'. Supported: "
                        + String.join(", ", SUPPORTED_STRATEGIES) + ".");
            }

            // "upgrade" strategy → use the new structural matcher + upgrade executor
            if (STRATEGY_UPGRADE.equals(requestedStrategy)) {
                if (isNullOrEmpty(targetAgentId) || targetAgentId.isBlank()) {
                    // Falling through to the create path here produced a brand-new
                    // duplicate agent and reported 201, so a dropped query parameter
                    // silently doubled the deployment's agent list.
                    throw new BadRequestException("strategy=upgrade requires targetAgentId.");
                }
                return executeUpgradeFromZip(zippedAgentConfigFiles, targetAgentId, selectedOriginIds, workflowOrder);
            }
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));

            Set<String> selectedSet = parseSelectedResources(selectedOriginIds);
            boolean isMerge = STRATEGY_MERGE.equals(requestedStrategy);

            return importAgentZipFile(zippedAgentConfigFiles, targetDir, isMerge, selectedSet);
        } catch (WebApplicationException e) {
            // 400/404 raised deliberately above (or by the upgrade path) must not be
            // repackaged as a 500 by the catch-all below.
            metrics.importFailed();
            throw e;
        } catch (IllegalArgumentException e) {
            // Config validation failure (e.g. invalid hitlConfig) → 400 via the
            // IllegalArgumentExceptionMapper, not a 500.
            metrics.importFailed();
            throw e;
        } catch (Exception e) {
            metrics.importFailed();
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        }
    }

    private Set<String> parseSelectedResources(String selectedOriginIds) {
        if (isNullOrEmpty(selectedOriginIds))
            return null;
        return Arrays.stream(selectedOriginIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private boolean isSelected(Set<String> selectedSet, String originId) {
        return selectedSet == null || selectedSet.contains(originId);
    }

    /**
     * Unzips and imports an agent, then guarantees two things the import itself
     * cannot: that a partial import leaves no orphaned resources behind, and that
     * the unzipped scratch directory is removed either way.
     * <p>
     * Every resource lands through its own store call, so a failure at, say, the
     * agent — the very last write — would otherwise leave all its workflows and
     * extensions permanently in the database.
     */
    private Response importAgentZipFile(InputStream zippedAgentConfigFiles, File targetDir, boolean isMerge,
                                        Set<String> selectedSet)
            throws IOException {

        var transaction = new ImportTransaction();
        try {
            return unpackAndImportAgent(zippedAgentConfigFiles, targetDir, isMerge, selectedSet, transaction);
        } catch (Throwable failure) {
            rollbackCreatedResources(transaction);
            throw failure;
        } finally {
            deleteTempDirectoryQuietly(targetDir.toPath());
        }
    }

    private Response unpackAndImportAgent(InputStream zippedAgentConfigFiles, File targetDir, boolean isMerge,
                                          Set<String> selectedSet, ImportTransaction transaction)
            throws IOException {

        this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);
        var targetDirPath = targetDir.getPath();

        // Import snippets (global resources, not workflow-embedded). They are
        // recorded on the transaction like everything else — a snippet this import
        // created is just as much an orphan as a workflow when a later resource
        // blows up.
        importSnippets(Paths.get(targetDirPath), isMerge, transaction);

        URI lastAgentUri = null;
        for (var agentFilePath : singleAgentFileIn(Paths.get(targetDirPath))) {
            try {
                String agentOriginId = extractIdFromAgentFilename(agentFilePath);
                String agentFileString = readFile(agentFilePath);

                // Normalize legacy eddi:// URIs from v5 ZIP exports to v6 canonical form
                agentFileString = normalizeLegacyUris(agentFileString);
                // Normalize legacy ${eddivault:...} → ${vault:...}
                agentFileString = normalizeVaultReferences(agentFileString);

                AgentConfiguration agentConfig = jsonSerialization.deserialize(agentFileString, AgentConfiguration.class);

                // Reject an invalid HITL config BEFORE importing workflows —
                // otherwise the store-level validation only fires at agent
                // creation, after all extensions already landed (partial
                // import), and surfaced as a 500 instead of a 400.
                HitlConfigValidation.validate(agentConfig.getHitlConfig());

                agentConfig.getWorkflows()
                        .forEach(workflowUri -> parseWorkflow(targetDirPath, workflowUri, agentConfig, isMerge, selectedSet, transaction));

                URI newAgentUri;
                if (isMerge && isSelected(selectedSet, agentOriginId)) {
                    newAgentUri = createOrUpdateAgent(agentConfig, agentOriginId, transaction);
                } else {
                    newAgentUri = createNewAgent(agentConfig, transaction);
                }

                // Schedules carry the agent id they fire, so they can only be written
                // once the agent exists — but before the descriptor bookkeeping, so a
                // failure there still rolls them back.
                importSchedules(Paths.get(targetDirPath), newAgentUri, transaction);

                updateDocumentDescriptor(Paths.get(targetDirPath), buildOldAgentUri(agentFilePath), newAgentUri);

                // Set originId on the new agent's descriptor
                setOriginIdOnDescriptor(newAgentUri, agentOriginId);

                lastAgentUri = newAgentUri;
            } catch (IOException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                throw new InternalServerErrorException(e.getLocalizedMessage(), e);
            }
        }
        LOGGER.infof("Import complete: lastAgentUri=%s", lastAgentUri);
        if (lastAgentUri != null) {
            // Use manual .header("Location", ...) instead of Response.created(URI)
            // because Response.created() validates the URI scheme and may strip eddi://
            // URIs
            return Response.status(Response.Status.CREATED)
                    .header("Location", lastAgentUri.toString()).build();
        }
        // Nothing was imported. Answering 200 with an empty resourceUri is how a
        // whole class of broken archives went unnoticed.
        throw noAgentFileFound();
    }

    private URI buildOldAgentUri(Path agentPath) {
        String agentPathString = agentPath.toString();
        String oldAgentId = agentPathString.substring(agentPathString.lastIndexOf(File.separator) + 1,
                agentPathString.lastIndexOf(agentFileEndingOf(agentPath)));

        return URI.create(IRestAgentStore.resourceURI + oldAgentId + IRestAgentStore.versionQueryParam + "1");
    }

    private void parseWorkflow(String targetDirPath, URI workflowUri, AgentConfiguration agentConfig, boolean isMerge,
                               Set<String> selectedSet, ImportTransaction transaction) {
        try {
            IResourceId workflowResourceId = RestUtilities.extractResourceId(workflowUri);
            if (workflowResourceId == null) {
                return;
            }
            String workflowId = workflowResourceId.getId();
            String workflowVersion = String.valueOf(workflowResourceId.getVersion());

            var dir = Paths.get(FileUtilities.buildPath(targetDirPath, workflowId, workflowVersion));
            try (var directoryStream = Files.newDirectoryStream(dir,
                    wfFilePath -> wfFilePath.toString().endsWith(".workflow.json") || wfFilePath.toString().endsWith(".package.json"))) {
                directoryStream.forEach(workflowFilePath -> {
                    try {
                        Path workflowPath = workflowFilePath.getParent();
                        String workflowFileString = readFile(workflowFilePath);

                        // Normalize legacy eddi:// URIs from v5 ZIP exports to v6 canonical form
                        workflowFileString = normalizeLegacyUris(workflowFileString);
                        // Normalize legacy ${eddivault:...} → ${vault:...}
                        workflowFileString = normalizeVaultReferences(workflowFileString);

                        // loading old resources, creating/updating them,
                        // updating document descriptor and replacing references in workflow config

                        // ... for dictionaries
                        List<URI> dictionaryUris = extractResourcesUris(workflowFileString, DICTIONARY_URI_PATTERN);
                        List<URI> newDictionaryUris = createOrUpdateResources(
                                readResources(dictionaryUris, workflowPath, DICTIONARY_EXT, DictionaryConfiguration.class, isMerge, selectedSet),
                                dictionaryUris, isMerge,
                                selectedSet, this::createNewDictionaries, this::updateDictionary, transaction);

                        updateDocumentDescriptor(workflowPath, dictionaryUris, newDictionaryUris);
                        workflowFileString = replaceURIs(workflowFileString, dictionaryUris, newDictionaryUris);

                        // ... for behavior
                        List<URI> behaviorUris = extractResourcesUris(workflowFileString, BEHAVIOR_URI_PATTERN);
                        List<URI> newBehaviorUris = createOrUpdateResources(
                                readResources(behaviorUris, workflowPath, BEHAVIOR_EXT, RuleSetConfiguration.class, isMerge, selectedSet),
                                behaviorUris, isMerge,
                                selectedSet, this::createNewBehaviors, this::updateBehavior, transaction);

                        updateDocumentDescriptor(workflowPath, behaviorUris, newBehaviorUris);
                        workflowFileString = replaceURIs(workflowFileString, behaviorUris, newBehaviorUris);

                        // ... for http calls
                        List<URI> httpCallsUris = extractResourcesUris(workflowFileString, HTTPCALLS_URI_PATTERN);
                        List<URI> newApiCallsUris = createOrUpdateResources(
                                readResources(httpCallsUris, workflowPath, HTTPCALLS_EXT, ApiCallsConfiguration.class, isMerge, selectedSet),
                                httpCallsUris, isMerge,
                                selectedSet, this::createNewApiCalls, this::updateApiCalls, transaction);

                        updateDocumentDescriptor(workflowPath, httpCallsUris, newApiCallsUris);
                        workflowFileString = replaceURIs(workflowFileString, httpCallsUris, newApiCallsUris);

                        // ... for langchain
                        List<URI> langchainUris = extractResourcesUris(workflowFileString, LANGCHAIN_URI_PATTERN);
                        List<URI> newLangchainUris = createOrUpdateResources(
                                readResources(langchainUris, workflowPath, LLM_EXT, LlmConfiguration.class, isMerge, selectedSet), langchainUris,
                                isMerge, selectedSet,
                                this::createNewLlm, this::updateLangchain, transaction);

                        updateDocumentDescriptor(workflowPath, langchainUris, newLangchainUris);
                        workflowFileString = replaceURIs(workflowFileString, langchainUris, newLangchainUris);

                        // ... for property
                        List<URI> propertyUris = extractResourcesUris(workflowFileString, PROPERTY_URI_PATTERN);
                        List<URI> newPropertyUris = createOrUpdateResources(
                                readResources(propertyUris, workflowPath, PROPERTY_EXT, PropertySetterConfiguration.class, isMerge, selectedSet),
                                propertyUris, isMerge,
                                selectedSet, this::createNewProperties, this::updateProperty, transaction);

                        updateDocumentDescriptor(workflowPath, propertyUris, newPropertyUris);
                        workflowFileString = replaceURIs(workflowFileString, propertyUris, newPropertyUris);

                        // ... for output
                        List<URI> outputUris = extractResourcesUris(workflowFileString, OUTPUT_URI_PATTERN);
                        List<URI> newOutputUris = createOrUpdateResources(
                                readResources(outputUris, workflowPath, OUTPUT_EXT, OutputConfigurationSet.class, isMerge, selectedSet), outputUris,
                                isMerge, selectedSet,
                                this::createNewOutputs, this::updateOutput, transaction);

                        updateDocumentDescriptor(workflowPath, outputUris, newOutputUris);
                        workflowFileString = replaceURIs(workflowFileString, outputUris, newOutputUris);

                        // ... for mcp calls
                        List<URI> mcpCallsUris = extractResourcesUris(workflowFileString, MCPCALLS_URI_PATTERN);
                        List<URI> newMcpCallsUris = createOrUpdateResources(
                                readResources(mcpCallsUris, workflowPath, MCPCALLS_EXT, McpCallsConfiguration.class, isMerge, selectedSet),
                                mcpCallsUris, isMerge,
                                selectedSet, this::createNewMcpCalls, this::updateMcpCalls, transaction);

                        updateDocumentDescriptor(workflowPath, mcpCallsUris, newMcpCallsUris);
                        workflowFileString = replaceURIs(workflowFileString, mcpCallsUris, newMcpCallsUris);

                        // ... for rag
                        List<URI> ragUris = extractResourcesUris(workflowFileString, RAG_URI_PATTERN);
                        List<URI> newRagUris = createOrUpdateResources(
                                readResources(ragUris, workflowPath, RAG_EXT, RagConfiguration.class, isMerge, selectedSet), ragUris, isMerge,
                                selectedSet,
                                this::createNewRags, this::updateRag, transaction);

                        updateDocumentDescriptor(workflowPath, ragUris, newRagUris);
                        workflowFileString = replaceURIs(workflowFileString, ragUris, newRagUris);

                        // creating updated workflow and replacing references in Agent config
                        URI newWorkflowUri;
                        if (isMerge && isSelected(selectedSet, workflowId)) {
                            newWorkflowUri = createOrUpdateWorkflow(workflowFileString, workflowId, transaction);
                        } else {
                            newWorkflowUri = createNewWorkflow(workflowFileString, transaction);
                        }

                        // Set originId on the workflow's descriptor
                        setOriginIdOnDescriptor(newWorkflowUri, workflowId);

                        updateDocumentDescriptor(workflowPath, workflowUri, newWorkflowUri);
                        agentConfig.setWorkflows(agentConfig.getWorkflows().stream().map(uri -> uri.equals(workflowUri) ? newWorkflowUri : uri)
                                .collect(Collectors.toList()));

                    } catch (IOException | CallbackMatcher.CallbackMatcherException e) {
                        LOGGER.error(e.getLocalizedMessage(), e);
                        throw new InternalServerErrorException(e.getMessage(), e);
                    }
                });

            }

        } catch (IOException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        }
    }

    // ==================== Create or Update Logic ====================

    @FunctionalInterface
    private interface ResourceCreator<T> {
        List<URI> create(List<T> configs, ImportTransaction transaction);
    }

    @FunctionalInterface
    private interface ResourceUpdater<T> {
        URI update(T config, String localId, Integer localVersion, ImportTransaction transaction);
    }

    private <T> List<URI> createOrUpdateResources(List<T> configs, List<URI> originUris, boolean isMerge, Set<String> selectedSet,
                                                  ResourceCreator<T> creator, ResourceUpdater<T> updater, ImportTransaction transaction) {

        if (!isMerge) {
            // Original behavior: create everything new
            List<URI> newUris = creator.create(configs, transaction);
            // Set originId on each newly created resource
            for (int i = 0; i < originUris.size() && i < newUris.size(); i++) {
                IResourceId origId = RestUtilities.extractResourceId(originUris.get(i));
                if (origId != null) {
                    setOriginIdOnDescriptor(newUris.get(i), origId.getId());
                }
            }
            return newUris;
        }

        // Merge strategy: check each resource for existing local copy
        List<URI> resultUris = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            T config = configs.get(i);
            IResourceId origResId = RestUtilities.extractResourceId(originUris.get(i));
            if (origResId == null) {
                resultUris.add(originUris.get(i));
                continue;
            }

            String originId = origResId.getId();
            if (!isSelected(selectedSet, originId)) {
                // Not selected — keep the local copy if this deployment has one.
                URI existingUri = findLocalUriByOriginId(originId);
                if (existingUri == null) {
                    // There is nothing to keep. Adding originUris.get(i) — the URI as
                    // it appeared in the SOURCE deployment — stored a workflow step
                    // pointing at a resource id that does not exist here, and the
                    // failure only surfaced later, at deployment or first turn.
                    throw new BadRequestException("Resource '" + originId + "' was excluded from the import,"
                            + " but this deployment has no copy of it and the imported workflow references it."
                            + " Include it in selectedResources, or remove its step from the workflow.");
                }
                resultUris.add(existingUri);
                continue;
            }

            // Try to find existing local resource
            URI existingUri = findLocalUriByOriginId(originId);
            if (existingUri != null) {
                // Update existing
                IResourceId localResId = RestUtilities.extractResourceId(existingUri);
                if (localResId != null) {
                    URI updatedUri = updater.update(config, localResId.getId(), localResId.getVersion(), transaction);
                    setOriginIdOnDescriptor(updatedUri, originId);
                    resultUris.add(updatedUri);
                    continue;
                }
            }

            // No existing resource found — create new
            List<URI> created = creator.create(List.of(config), transaction);
            if (!created.isEmpty()) {
                setOriginIdOnDescriptor(created.getFirst(), originId);
                resultUris.add(created.getFirst());
            }
        }
        return resultUris;
    }

    private URI findLocalUriByOriginId(String originId) {
        try {
            // Try by originId first (standard merge path)
            List<DocumentDescriptor> existing = documentDescriptorStore.findByOriginId(originId);
            if (!existing.isEmpty()) {
                return existing.getFirst().getResource();
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            LOGGER.debug("Could not look up origin ID " + originId + ": " + e.getMessage());
        }

        // Fallback: try by resource ID directly (handles export→re-import round-trip
        // where the exported filename IS the resource ID, not the original originId)
        try {
            IResourceId currentId = documentDescriptorStore.getCurrentResourceId(originId);
            if (currentId != null) {
                DocumentDescriptor desc = documentDescriptorStore.readDescriptor(currentId.getId(), currentId.getVersion());
                if (desc != null) {
                    return desc.getResource();
                }
            }
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            LOGGER.debugf("Fallback resource ID lookup for '%s' not found: %s", originId, e.getMessage());
        }
        return null;
    }

    private URI createOrUpdateAgent(AgentConfiguration agentConfiguration, String agentOriginId, ImportTransaction transaction) {
        URI existingUri = findLocalUriByOriginId(agentOriginId);
        if (existingUri != null) {
            IResourceId localResId = RestUtilities.extractResourceId(existingUri);
            if (localResId != null) {
                IRestAgentStore restAgentStore = getRestResourceStore(IRestAgentStore.class);
                Response updateResponse = restAgentStore.updateAgent(localResId.getId(), localResId.getVersion(), agentConfiguration);
                if (updateResponse.getStatus() == 200) {
                    // updated — new version = old version + 1
                    int newVersion = localResId.getVersion() + 1;
                    return URI.create(IRestAgentStore.resourceURI + localResId.getId() + IRestAgentStore.versionQueryParam + newVersion);
                }
            }
        }
        return createNewAgent(agentConfiguration, transaction);
    }

    private URI createOrUpdateWorkflow(String workflowFileString, String workflowOriginId, ImportTransaction transaction)
            throws IOException {
        URI existingUri = findLocalUriByOriginId(workflowOriginId);
        if (existingUri != null) {
            IResourceId localResId = RestUtilities.extractResourceId(existingUri);
            if (localResId != null) {
                WorkflowConfiguration workflowConfig = jsonSerialization.deserialize(workflowFileString, WorkflowConfiguration.class);
                IRestWorkflowStore restWorkflowStore = getRestResourceStore(IRestWorkflowStore.class);
                Response updateResponse = restWorkflowStore.updateWorkflow(localResId.getId(), localResId.getVersion(), workflowConfig);
                if (updateResponse.getStatus() == 200) {
                    int newVersion = localResId.getVersion() + 1;
                    return URI.create(IRestWorkflowStore.resourceURI + localResId.getId() + IRestWorkflowStore.versionQueryParam + newVersion);
                }
            }
        }
        return createNewWorkflow(workflowFileString, transaction);
    }

    private void setOriginIdOnDescriptor(URI resourceUri, String originId) {
        try {
            IResourceId resourceId = RestUtilities.extractResourceId(resourceUri);
            if (resourceId != null) {
                // Use getCurrentResourceId to find the descriptor's actual version.
                // During merge, the resource may be at v2 but the descriptor still at v1
                // (updateDocumentDescriptor runs later). Reading at the resource version
                // would fail with ResourceNotFoundException.
                IResourceId currentDescId = documentDescriptorStore.getCurrentResourceId(resourceId.getId());
                if (currentDescId != null) {
                    DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(
                            currentDescId.getId(), currentDescId.getVersion());
                    if (descriptor != null && !originId.equals(descriptor.getOriginId())) {
                        descriptor.setOriginId(originId);
                        documentDescriptorStore.setDescriptor(
                                currentDescId.getId(), currentDescId.getVersion(), descriptor);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not set originId on descriptor for " + resourceUri + ": " + e.getMessage());
        }
    }

    // ==================== Resource Creation ====================
    //
    // All create methods use direct I*Store.create() via CDI instead of going
    // through
    // the IRest*Store layer. This bypasses Response.getLocation() which returns
    // null
    // for eddi:// scheme URIs when called in-process (CDI direct calls).

    /**
     * Creates a resource directly via CDI store lookup, bypassing the REST layer
     * entirely. Returns the constructed URI for the new resource.
     * <p>
     * Every creation is recorded on the {@link ImportTransaction} so a later
     * failure can delete it again.
     */
    private <T> URI createResourceDirect(Class<?> storeClass, T document, String resourceUri, ImportTransaction transaction) {
        try {
            IResourceStore<T> store = resolveStore(storeClass);
            IResourceId resourceId = store.create(document);
            transaction.recordCreated(storeClass, resourceId);
            URI createdUri = RestUtilities.createURI(resourceUri, resourceId.getId(), IRestVersionInfo.versionQueryParam, resourceId.getVersion());

            // Create the DocumentDescriptor that the DocumentDescriptorFilter would
            // normally create on a 201 response. Since we bypass the REST layer,
            // the filter never runs, so we must create it manually — including the
            // ownership stamp, or every imported resource would be unowned.
            documentDescriptorStore.createDescriptor(
                    resourceId.getId(), resourceId.getVersion(),
                    resourceAccessGuard.stampNewDescriptor(createDocumentDescriptor(createdUri)));

            return createdUri;
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    /** Looks up a resource store bean by its interface class. */
    @SuppressWarnings("unchecked")
    private <T> IResourceStore<T> resolveStore(Class<?> storeClass) {
        return (IResourceStore<T>) CDI.current().select(storeClass).get();
    }

    private URI createNewAgent(AgentConfiguration agentConfiguration, ImportTransaction transaction) {
        return createResourceDirect(IAgentStore.class, agentConfiguration, IRestAgentStore.resourceURI, transaction);
    }

    private URI createNewWorkflow(String workflowFileString, ImportTransaction transaction) throws IOException {
        WorkflowConfiguration workflowConfig = jsonSerialization.deserialize(workflowFileString, WorkflowConfiguration.class);
        return createResourceDirect(IWorkflowStore.class, workflowConfig, IRestWorkflowStore.resourceURI, transaction);
    }

    private List<URI> createNewDictionaries(List<DictionaryConfiguration> configs, ImportTransaction transaction) {
        return configs.stream().map(c -> createResourceDirect(IDictionaryStore.class, c, IRestDictionaryStore.resourceURI, transaction)).toList();
    }

    private List<URI> createNewBehaviors(List<RuleSetConfiguration> configs, ImportTransaction transaction) {
        return configs.stream().map(c -> createResourceDirect(IRuleSetStore.class, c, IRestRuleSetStore.resourceURI, transaction)).toList();
    }

    private List<URI> createNewApiCalls(List<ApiCallsConfiguration> configs, ImportTransaction transaction) {
        return configs.stream().map(c -> createResourceDirect(IApiCallsStore.class, c, IRestApiCallsStore.resourceURI, transaction)).toList();
    }

    private List<URI> createNewLlm(List<LlmConfiguration> configs, ImportTransaction transaction) {
        return configs.stream().map(c -> createResourceDirect(ILlmStore.class, c, IRestLlmStore.resourceURI, transaction)).toList();
    }

    private List<URI> createNewProperties(List<PropertySetterConfiguration> configs, ImportTransaction transaction) {
        return configs.stream()
                .map(c -> createResourceDirect(IPropertySetterStore.class, c, IRestPropertySetterStore.resourceURI, transaction)).toList();
    }

    private List<URI> createNewOutputs(List<OutputConfigurationSet> configs, ImportTransaction transaction) {
        return configs.stream().map(c -> createResourceDirect(IOutputStore.class, c, IRestOutputStore.resourceURI, transaction)).toList();
    }

    // ==================== Resource Update (merge logic) ====================

    private URI updateDictionary(DictionaryConfiguration config, String localId, Integer localVersion, ImportTransaction transaction) {
        IRestDictionaryStore store = getRestResourceStore(IRestDictionaryStore.class);
        Response response = store.updateRegularDictionary(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestDictionaryStore.resourceURI + localId + IRestDictionaryStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IDictionaryStore.class, config, IRestDictionaryStore.resourceURI, transaction);
    }

    private URI updateBehavior(RuleSetConfiguration config, String localId, Integer localVersion, ImportTransaction transaction) {
        IRestRuleSetStore store = getRestResourceStore(IRestRuleSetStore.class);
        Response response = store.updateRuleSet(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestRuleSetStore.resourceURI + localId + IRestRuleSetStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IRuleSetStore.class, config, IRestRuleSetStore.resourceURI, transaction);
    }

    private URI updateApiCalls(ApiCallsConfiguration config, String localId, Integer localVersion, ImportTransaction transaction) {
        IRestApiCallsStore store = getRestResourceStore(IRestApiCallsStore.class);
        Response response = store.updateApiCalls(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestApiCallsStore.resourceURI + localId + IRestApiCallsStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IApiCallsStore.class, config, IRestApiCallsStore.resourceURI, transaction);
    }

    private URI updateLangchain(LlmConfiguration config, String localId, Integer localVersion, ImportTransaction transaction) {
        IRestLlmStore store = getRestResourceStore(IRestLlmStore.class);
        Response response = store.updateLlm(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestLlmStore.resourceURI + localId + IRestLlmStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(ILlmStore.class, config, IRestLlmStore.resourceURI, transaction);
    }

    private URI updateProperty(PropertySetterConfiguration config, String localId, Integer localVersion, ImportTransaction transaction) {
        IRestPropertySetterStore store = getRestResourceStore(IRestPropertySetterStore.class);
        Response response = store.updatePropertySetter(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestPropertySetterStore.resourceURI + localId + IRestPropertySetterStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IPropertySetterStore.class, config, IRestPropertySetterStore.resourceURI, transaction);
    }

    private URI updateOutput(OutputConfigurationSet config, String localId, Integer localVersion, ImportTransaction transaction) {
        IRestOutputStore store = getRestResourceStore(IRestOutputStore.class);
        Response response = store.updateOutputSet(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestOutputStore.resourceURI + localId + IRestOutputStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IOutputStore.class, config, IRestOutputStore.resourceURI, transaction);
    }

    private List<URI> createNewMcpCalls(List<McpCallsConfiguration> configs, ImportTransaction transaction) {
        return configs.stream().map(c -> createResourceDirect(IMcpCallsStore.class, c, IRestMcpCallsStore.resourceURI, transaction)).toList();
    }

    private URI updateMcpCalls(McpCallsConfiguration config, String localId, Integer localVersion, ImportTransaction transaction) {
        IRestMcpCallsStore store = getRestResourceStore(IRestMcpCallsStore.class);
        Response response = store.updateMcpCalls(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestMcpCallsStore.resourceURI + localId + IRestMcpCallsStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IMcpCallsStore.class, config, IRestMcpCallsStore.resourceURI, transaction);
    }

    private List<URI> createNewRags(List<RagConfiguration> configs, ImportTransaction transaction) {
        return configs.stream().map(c -> createResourceDirect(IRagStore.class, c, IRestRagStore.resourceURI, transaction)).toList();
    }

    private URI updateRag(RagConfiguration config, String localId, Integer localVersion, ImportTransaction transaction) {
        IRestRagStore store = getRestResourceStore(IRestRagStore.class);
        Response response = store.updateRag(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestRagStore.resourceURI + localId + IRestRagStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IRagStore.class, config, IRestRagStore.resourceURI, transaction);
    }

    // ==================== Snippet Import ====================

    private void importSnippets(Path targetDirPath, boolean isMerge, ImportTransaction transaction) {
        try {
            // Look for snippets directory — could be inside the agent subdirectory
            Path snippetsDir = findSnippetsDir(targetDirPath);
            if (snippetsDir == null || !Files.exists(snippetsDir)) {
                return;
            }

            try (var snippetStream = Files.newDirectoryStream(snippetsDir,
                    p -> p.toString().endsWith("." + SNIPPET_EXT + ".json"))) {

                IRestPromptSnippetStore restSnippetStore = getRestResourceStore(IRestPromptSnippetStore.class);

                // Build a name → (id, version) map of existing snippets for name-based
                // deduplication.
                // Snippet name is the natural key (e.g., "cautious_mode") — not the MongoDB
                // document ID
                // which differs across EDDI instances.
                Map<String, IResourceId> existingSnippetsByName = buildExistingSnippetNameMap(restSnippetStore);

                int importedCount = 0;
                int skippedCount = 0;
                for (Path snippetFilePath : snippetStream) {
                    try {
                        String snippetJson = readFile(snippetFilePath);
                        PromptSnippet snippet = jsonSerialization.deserialize(snippetJson, PromptSnippet.class);
                        if (snippet == null || snippet.getName() == null)
                            continue;

                        String snippetName = snippet.getName();

                        // Always check for name collision — snippets are global resources,
                        // duplicates cause unpredictable runtime behavior regardless of strategy
                        if (existingSnippetsByName.containsKey(snippetName)) {
                            if (isMerge) {
                                // Merge strategy: update existing snippet with imported content
                                IResourceId localResId = existingSnippetsByName.get(snippetName);
                                Response updateResp = restSnippetStore.updateSnippet(
                                        localResId.getId(), localResId.getVersion(), snippet);
                                if (updateResp.getStatus() == 200) {
                                    LOGGER.debugf("Updated existing snippet '%s' (id=%s, v=%d)",
                                            snippetName, localResId.getId(), localResId.getVersion());
                                    importedCount++;
                                    continue;
                                }
                                // Update failed (e.g., version conflict) — fall through to create
                                LOGGER.warnf("Update failed for snippet '%s' (status=%d), creating new",
                                        snippetName, updateResp.getStatus());
                            } else {
                                // Create strategy: snippet already exists globally, skip to avoid duplicates
                                LOGGER.debugf("Snippet '%s' already exists, skipping (create strategy)", snippetName);
                                skippedCount++;
                                continue;
                            }
                        }

                        // Create new snippet
                        Response createResp = restSnippetStore.createSnippet(snippet);
                        checkIfCreatedResponse(createResp);
                        recordCreatedSnippet(createResp, transaction);
                        importedCount++;
                        LOGGER.debugf("Created new snippet '%s'", snippetName);
                    } catch (Exception e) {
                        LOGGER.warnf("Failed to import snippet from %s: %s", snippetFilePath, e.getMessage());
                    }
                }
                if (importedCount > 0 || skippedCount > 0) {
                    LOGGER.infof("Snippets: imported %d, skipped %d (already exist)", importedCount, skippedCount);
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to import snippets: %s", e.getMessage());
        }
    }

    /**
     * Records a snippet this import just created so a later failure can delete it
     * again — without this, {@link #rollbackCreatedResources} never sees snippets
     * and a partial import leaves them behind, contradicting the guarantee
     * {@link #importAgentZipFile} advertises.
     * <p>
     * The id is read from the {@code X-Resource-URI} header rather than
     * {@code Response.getLocation()}, which JAX-RS reports as {@code null} for the
     * {@code eddi://} scheme on an in-process call. A snippet that was only
     * <em>updated</em> during a merge is deliberately not recorded: it already
     * existed and is not an orphan.
     */
    private void recordCreatedSnippet(Response createResponse, ImportTransaction transaction) {
        if (createResponse.getStatus() != 201) {
            return;
        }
        String createdUri = createResponse.getHeaderString("X-Resource-URI");
        if (createdUri == null) {
            LOGGER.warn("Created snippet carries no resource URI — it cannot be rolled back if the import fails");
            return;
        }
        transaction.recordCreated(IPromptSnippetStore.class, RestUtilities.extractResourceId(URI.create(createdUri)));
    }

    /**
     * Builds a map of existing snippet names → resource IDs by loading all snippet
     * descriptors and their configs. This enables name-based deduplication during
     * import — the natural key for snippets is their {@code name} field, not the
     * MongoDB document ID.
     */
    private Map<String, IResourceId> buildExistingSnippetNameMap(IRestPromptSnippetStore restSnippetStore) {
        Map<String, IResourceId> nameMap = new LinkedHashMap<>();
        try {
            List<DocumentDescriptor> descriptors = restSnippetStore.readSnippetDescriptors("", 0, 0);
            if (descriptors == null)
                return nameMap;

            for (DocumentDescriptor descriptor : descriptors) {
                try {
                    IResourceId resourceId = RestUtilities.extractResourceId(descriptor.getResource());
                    if (resourceId == null)
                        continue;

                    PromptSnippet existing = restSnippetStore.readSnippet(resourceId.getId(), resourceId.getVersion());
                    if (existing != null && existing.getName() != null) {
                        nameMap.put(existing.getName(), resourceId);
                    }
                } catch (Exception e) {
                    LOGGER.debugf("Could not load snippet for dedup: %s", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not build snippet name map: %s", e.getMessage());
        }
        return nameMap;
    }

    private Path findSnippetsDir(Path targetDirPath) {
        return findArchiveDir(targetDirPath, "snippets");
    }

    /**
     * Finds a top-level archive directory such as {@code snippets/} or
     * {@code schedules/}, tolerating the extra {@code <agentId>/} and
     * {@code <agentId>/<version>/} nesting a hand-built archive can have.
     */
    private Path findArchiveDir(Path targetDirPath, String dirName) {
        // Check directly under target dir
        Path direct = Paths.get(targetDirPath.toString(), dirName);
        if (Files.exists(direct)) {
            return direct;
        }

        // Check inside agent subdirectories (the ZIP structure nests under
        // agentId/version/)
        try (var dirStream = Files.newDirectoryStream(targetDirPath, Files::isDirectory)) {
            for (Path subDir : dirStream) {
                // Look in agentId/ directory
                Path nested = Paths.get(subDir.toString(), dirName);
                if (Files.exists(nested)) {
                    return nested;
                }
                // Look in agentId/version/ directories
                try (var versionStream = Files.newDirectoryStream(subDir, Files::isDirectory)) {
                    for (Path versionDir : versionStream) {
                        Path deepNested = Paths.get(versionDir.toString(), dirName);
                        if (Files.exists(deepNested)) {
                            return deepNested;
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.debugf("Error searching for %s directory: %s", dirName, e.getMessage());
        }
        return null;
    }

    // ==================== Schedule Import ====================

    /**
     * Recreates the agent's scheduled triggers from the archive's
     * {@code schedules/} directory.
     * <p>
     * Export has always written these files; nothing read them back, so restoring
     * an agent from a backup brought up an agent whose nightly jobs and heartbeats
     * had silently stopped — while the ZIP visibly contained them, which is what
     * made the gap look like a success.
     * <p>
     * A schedule is always <em>created</em>, never merged: its id is the store's,
     * not a portable resource id, and its {@code agentId} is repointed at the agent
     * this import just created. Fire bookkeeping (claim state, retry counters, last
     * fire) is reset so an imported schedule starts clean rather than inheriting
     * another deployment's in-flight lease.
     * <p>
     * A schedule that cannot be created fails the whole import rather than being
     * logged and skipped: the point of importing schedules at all is that an agent
     * restored without its nightly job looks complete and is not.
     */
    private void importSchedules(Path targetDirPath, URI newAgentUri, ImportTransaction transaction) {
        Path schedulesDir = findArchiveDir(targetDirPath, SCHEDULES_DIR);
        if (schedulesDir == null) {
            return;
        }
        IResourceId agentResourceId = RestUtilities.extractResourceId(newAgentUri);
        if (agentResourceId == null || agentResourceId.getId() == null) {
            throw new InternalServerErrorException(
                    "The archive contains schedules but the imported agent URI " + newAgentUri + " carries no id.");
        }

        List<Path> scheduleFiles = new ArrayList<>();
        try (var scheduleStream = Files.newDirectoryStream(schedulesDir,
                p -> p.toString().endsWith("." + SCHEDULE_EXT + ".json"))) {
            scheduleStream.forEach(scheduleFiles::add);
        } catch (IOException e) {
            throw new InternalServerErrorException("Could not read schedules from the archive: " + e.getMessage(), e);
        }

        int imported = 0;
        for (Path scheduleFilePath : scheduleFiles) {
            ScheduleConfiguration schedule;
            try {
                schedule = jsonSerialization.deserialize(readFile(scheduleFilePath), ScheduleConfiguration.class);
            } catch (Exception e) {
                throw new BadRequestException("Could not read '" + scheduleFilePath.getFileName()
                        + "' from the archive: " + e.getMessage(), e);
            }
            if (schedule == null) {
                continue;
            }
            prepareScheduleForImport(schedule, agentResourceId.getId());
            try {
                String scheduleId = scheduleStore.createSchedule(schedule);
                transaction.recordCompensation(() -> deleteScheduleQuietly(scheduleId));
            } catch (Exception e) {
                throw new InternalServerErrorException("Could not create the schedule from '"
                        + scheduleFilePath.getFileName() + "': " + e.getMessage(), e);
            }
            imported++;
        }

        if (imported > 0) {
            LOGGER.infof("Schedules: imported %d for agent %s", imported, agentResourceId.getId());
        }
    }

    /**
     * Repoints a schedule at the imported agent and clears its fire bookkeeping.
     */
    private static void prepareScheduleForImport(ScheduleConfiguration schedule, String newAgentId) {
        schedule.setId(null);
        schedule.setAgentId(newAgentId);
        schedule.setFireStatus(FireStatus.PENDING);
        schedule.setClaimedBy(null);
        schedule.setClaimedAt(null);
        schedule.setFireId(null);
        schedule.setFailCount(0);
        schedule.setNextRetryAt(null);
        schedule.setLastFired(null);
    }

    private void deleteScheduleQuietly(String scheduleId) {
        try {
            scheduleStore.deleteSchedule(scheduleId);
        } catch (Exception e) {
            LOGGER.warnf("Rollback could not delete schedule '%s': %s",
                    LogSanitizer.sanitize(scheduleId), LogSanitizer.sanitize(e.getMessage()));
        }
    }

    // ==================== Rollback of a partial import ====================

    /**
     * Records the resources one import run created, so a failure part-way through
     * can be compensated with deletes.
     * <p>
     * An instance belongs to exactly one import run and never escapes it, which
     * keeps the service itself stateless — state lives in the call, not in the
     * singleton.
     */
    private static final class ImportTransaction {

        /** A resource this import created, and the store that owns it. */
        private record CreatedResource(Class<?> storeClass, String id, Integer version) {
        }

        private final List<CreatedResource> created = new ArrayList<>();

        /**
         * Undo actions for things this import created that are not
         * {@code IResourceStore} resources — a schedule, for instance, lives in
         * {@link IScheduleStore} and has no version or descriptor.
         */
        private final List<Runnable> compensations = new ArrayList<>();

        void recordCreated(Class<?> storeClass, IResourceId resourceId) {
            if (storeClass == null || resourceId == null || resourceId.getId() == null) {
                return;
            }
            created.add(new CreatedResource(storeClass, resourceId.getId(), resourceId.getVersion()));
        }

        void recordCompensation(Runnable undo) {
            if (undo != null) {
                compensations.add(undo);
            }
        }

        /** Newest first — compensating deletes undo creations in reverse order. */
        List<CreatedResource> createdNewestFirst() {
            List<CreatedResource> reversed = new ArrayList<>(created);
            Collections.reverse(reversed);
            return reversed;
        }

        /** Newest first, for the same reason. */
        List<Runnable> compensationsNewestFirst() {
            List<Runnable> reversed = new ArrayList<>(compensations);
            Collections.reverse(reversed);
            return reversed;
        }
    }

    /**
     * Deletes everything the failed import created, newest first, so a ZIP that
     * blows up on its last resource leaves no orphans behind.
     * <p>
     * Resources that already existed and were merely <em>updated</em> during a
     * merge are deliberately left alone: they are not orphans, and their previous
     * versions remain in the store's history.
     * <p>
     * Every step is guarded — a rollback failure is logged, never thrown, so it can
     * never mask the original error.
     */
    private void rollbackCreatedResources(ImportTransaction transaction) {
        for (Runnable compensation : transaction.compensationsNewestFirst()) {
            try {
                compensation.run();
            } catch (Exception e) {
                LOGGER.warnf("Rollback compensation failed: %s", LogSanitizer.sanitize(e.getMessage()));
            }
        }

        List<ImportTransaction.CreatedResource> created = transaction.createdNewestFirst();
        if (created.isEmpty()) {
            return;
        }

        LOGGER.warnf("Import failed — rolling back %d resource(s) created so far", created.size());
        for (ImportTransaction.CreatedResource resource : created) {
            try {
                resolveStore(resource.storeClass()).deleteAllPermanently(resource.id());
                LOGGER.debugf("Rollback deleted %s '%s' (v%s)",
                        resource.storeClass().getSimpleName(), LogSanitizer.sanitize(resource.id()), resource.version());
            } catch (Exception e) {
                LOGGER.warnf("Rollback could not delete %s '%s': %s",
                        resource.storeClass().getSimpleName(), LogSanitizer.sanitize(resource.id()), LogSanitizer.sanitize(e.getMessage()));
            }
            try {
                documentDescriptorStore.deleteAllDescriptor(resource.id());
            } catch (Exception e) {
                LOGGER.warnf("Rollback could not delete descriptor of '%s': %s", LogSanitizer.sanitize(resource.id()),
                        LogSanitizer.sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Removes a directory an import unzipped into. Import unpacks every ZIP into
     * {@code tmp/import/<uuid>/}; without this the tree survives every request and
     * the directory grows without bound.
     */
    private static void deleteTempDirectoryQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.debugf("Could not delete temp file %s: %s", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOGGER.warnf("Could not clean up temp import directory %s: %s", directory, e.getMessage());
        }
    }

    // ==================== Shared helpers ====================

    private void updateDocumentDescriptor(Path directoryPath, URI oldUri, URI newUri) {
        updateDocumentDescriptor(directoryPath, Collections.singletonList(oldUri), Collections.singletonList(newUri));
    }

    private void updateDocumentDescriptor(Path directoryPath, List<URI> oldUris, List<URI> newUris) {

        IntStream.range(0, oldUris.size()).forEach(idx -> {
            try {
                URI oldUri = oldUris.get(idx);
                IResourceId oldResourceId = RestUtilities.extractResourceId(oldUri);
                if (oldResourceId != null) {
                    var zipDescriptor = readDocumentDescriptorFromFile(directoryPath, oldResourceId);

                    URI newUri = newUris.get(idx);
                    IResourceId newResourceId = RestUtilities.extractResourceId(newUri);

                    if (newResourceId != null) {
                        // Use documentDescriptorStore directly instead of the REST layer's
                        // patchDescriptor. CDI-direct resource updates bypass the
                        // DocumentDescriptorFilter, so the descriptor stays at its original
                        // version.
                        try {
                            IResourceId currentDescriptorId = documentDescriptorStore.getCurrentResourceId(newResourceId.getId());
                            if (currentDescriptorId != null) {
                                DocumentDescriptor existingDescriptor = documentDescriptorStore.readDescriptor(
                                        currentDescriptorId.getId(), currentDescriptorId.getVersion());

                                // Apply name/description from the ZIP's descriptor
                                if (zipDescriptor.getName() != null) {
                                    existingDescriptor.setName(zipDescriptor.getName());
                                }
                                if (zipDescriptor.getDescription() != null) {
                                    existingDescriptor.setDescription(zipDescriptor.getDescription());
                                }
                                // Update the resource URI to point to the new version
                                existingDescriptor.setResource(newUri);
                                // Only name and description are taken from the ZIP, so ownership is
                                // unchanged here — but a descriptor written before the access index
                                // existed acquires one on this write rather than staying unlistable
                                // until the backfill migration runs.
                                DescriptorAccess.rebuildIndex(existingDescriptor);

                                if (currentDescriptorId.getVersion() < newResourceId.getVersion()) {
                                    // MERGE path: descriptor version is behind the resource version
                                    // (e.g., descriptor v1 but resource v2). Use updateDescriptor to
                                    // bump the descriptor version. This prevents the
                                    // DocumentDescriptorFilter from creating a duplicate when it
                                    // sees the 201 response with the new resource version.
                                    documentDescriptorStore.updateDescriptor(
                                            currentDescriptorId.getId(), currentDescriptorId.getVersion(), existingDescriptor);
                                } else {
                                    // CREATE path: descriptor version already matches the resource
                                    // version (both v1). Use setDescriptor for an in-place update
                                    // without archiving to history. This avoids conflicts with
                                    // subsequent setOriginIdOnDescriptor calls.
                                    documentDescriptorStore.setDescriptor(
                                            currentDescriptorId.getId(), currentDescriptorId.getVersion(), existingDescriptor);
                                }
                            }
                        } catch (IResourceStore.ResourceNotFoundException e) {
                            // No existing descriptor — create one for the new resource.
                            //
                            // The ZIP's descriptor is UNTRUSTED input as far as ownership goes: it
                            // was written by another deployment, where the same principal and team
                            // names mean something else, and honouring it would let a file decide
                            // who owns a resource here and who it is shared with — publishing it,
                            // or filing it under someone else's name, with none of the checks the
                            // sharing API applies. Strip that, then stamp the importing user, so
                            // an import is owned by whoever performed it.
                            zipDescriptor.setResource(newUri);
                            documentDescriptorStore.createDescriptor(newResourceId.getId(), newResourceId.getVersion(),
                                    resourceAccessGuard.stampNewDescriptor(DescriptorAccess.stripOwnership(zipDescriptor)));
                        }
                    }
                }
            } catch (IOException | IResourceStore.ResourceStoreException | IResourceStore.ResourceModifiedException e) {
                LOGGER.error(e.getLocalizedMessage(), e);
            }
        });
    }

    private DocumentDescriptor readDocumentDescriptorFromFile(Path workflowPath, IResourceId resourceId) throws IOException {
        Path filePath = Paths.get(FileUtilities.buildPath(workflowPath.toString(), resourceId.getId() + ".descriptor.json"));
        String oldDocumentDescriptorFile = readFile(filePath);
        return jsonSerialization.deserialize(oldDocumentDescriptorFile, DocumentDescriptor.class);
    }

    private String replaceURIs(String resourceString, List<URI> oldUris, List<URI> newUris) throws CallbackMatcher.CallbackMatcherException {
        Map<String, String> uriMap = toMap(oldUris, newUris);
        CallbackMatcher callbackMatcher = new CallbackMatcher(EDDI_URI_PATTERN);
        return callbackMatcher.replaceMatches(resourceString, matchResult -> {
            String match = matchResult.group();
            String key = match.substring(1, match.length() - 1);
            return uriMap.containsKey(key) ? "\"" + uriMap.get(key) + "\"" : null;
        });
    }

    private Map<String, String> toMap(List<URI> oldUris, List<URI> newUris) {
        Map<String, String> ret = new LinkedHashMap<>();
        for (int i = 0; i < oldUris.size(); i++) {
            ret.put(oldUris.get(i).toString(), newUris.get(i).toString());
        }
        return ret;
    }

    /**
     * Looks up a REST store bean directly through CDI.
     * <p>
     * No REST proxy is involved despite the name: the MP REST Client proxy strips
     * response headers (Location, X-Resource-URI) and runs on the Vert.x IO event
     * loop, which deadlocks during import. It therefore cannot throw
     * {@code RestInterfaceFactoryException} either — that checked exception used to
     * be declared here and propagated through a dozen signatures, telling every
     * reader a REST call was happening.
     */
    private <T> T getRestResourceStore(Class<T> clazz) {
        return CDI.current().select(clazz).get();
    }

    /**
     * Loads every config a workflow references from the unzipped archive.
     * <p>
     * A config that cannot be read fails the import with a 400 naming the file.
     * Returning null instead put that null straight into the list handed to
     * {@code store.create(...)}, so a selectively-exported ZIP — whose workflow
     * still carries the URIs of the files the export left out — died with a bare
     * NullPointerException surfaced as a 500 whose message was literally "null".
     *
     * @param isMerge
     *            whether this is a merge import, where a resource the caller
     *            excluded is answered from the local deployment instead of the
     *            archive
     * @param selectedSet
     *            the caller's resource selection, or null for "everything"
     */
    private <T> List<T> readResources(List<URI> uris, Path workflowPath, String extension, Class<T> clazz,
                                      boolean isMerge, Set<String> selectedSet) {
        return uris.stream()
                .map(uri -> readResource(uri, workflowPath, extension, clazz, isMerge, selectedSet))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private <T> T readResource(URI uri, Path workflowPath, String extension, Class<T> clazz,
                               boolean isMerge, Set<String> selectedSet) {
        IResourceId resourceId = RestUtilities.extractResourceId(uri);
        if (resourceId == null || resourceId.getId() == null) {
            throw new BadRequestException("The archive references '" + uri + "', which carries no resource id.");
        }

        Path resourcePath = createResourcePath(workflowPath, resourceId.getId(), extension);
        if (!Files.exists(resourcePath)) {
            if (isMerge && !isSelected(selectedSet, resourceId.getId())) {
                // A merge that deliberately excluded this resource never looks at its
                // content — createOrUpdateResources answers it from the local copy, or
                // fails naming it when there is none. EDDI's own selective export omits
                // the file while leaving the reference in the workflow, so rejecting it
                // here made the product refuse archives it had just written.
                LOGGER.debugf("Archive omits %s for excluded resource %s — keeping the local copy",
                        resourcePath.getFileName(), resourceId.getId());
                return null;
            }
            throw new BadRequestException("The archive references '" + uri + "' but does not contain '"
                    + resourcePath.getFileName() + "'. A selective export that omits a configuration must also drop"
                    + " its reference from the workflow it belongs to.");
        }

        String resourceContent;
        try {
            resourceContent = readFile(resourcePath);
            if (uri.toString().startsWith(IRestPropertySetterStore.resourceBaseType)) {
                var resourceAsMap = jsonSerialization.deserialize(resourceContent, Map.class);
                var migratedPropertySetterDocument = migrationManager.migratePropertySetter().migrate(new Document(resourceAsMap));

                if (migratedPropertySetterDocument != null) {
                    resourceContent = jsonSerialization.serialize(migratedPropertySetterDocument);
                }
            } else if (uri.toString().startsWith(IRestApiCallsStore.resourceBaseType)) {
                var resourceAsMap = jsonSerialization.deserialize(resourceContent, Map.class);
                var migratedApiCallsDocument = migrationManager.migrateApiCalls().migrate(new Document(resourceAsMap));

                if (migratedApiCallsDocument != null) {
                    resourceContent = jsonSerialization.serialize(migratedApiCallsDocument);
                }
            } else if (uri.toString().startsWith(IRestOutputStore.resourceBaseType)) {
                var resourceAsMap = jsonSerialization.deserialize(resourceContent, Map.class);
                var migratedOutputDocument = migrationManager.migrateOutput().migrate(new Document(resourceAsMap));

                if (migratedOutputDocument != null) {
                    resourceContent = jsonSerialization.serialize(migratedOutputDocument);
                }
            }

            // Normalize legacy ${eddivault:...} → ${vault:...}
            resourceContent = normalizeVaultReferences(resourceContent);

            // Final pass: migrate any remaining Thymeleaf template syntax to Qute
            resourceContent = templateSyntaxMigrator.migrate(resourceContent);

            return jsonSerialization.deserialize(resourceContent, clazz);
        } catch (Exception e) {
            // One line with the throwable attached, and no dump of the config body:
            // the file name is the diagnostic that was missing, the body is not.
            LOGGER.errorf(e, "Failed to read %s (referenced as %s)", resourcePath, uri);
            throw new BadRequestException("Could not read '" + resourcePath.getFileName()
                    + "' from the archive: " + e.getMessage(), e);
        }
    }

    private Path createResourcePath(Path workflowPath, String resourceId, String extension) {
        return Paths.get(FileUtilities.buildPath(workflowPath.toString(), resourceId + "." + extension + ".json"));
    }

    private String readFile(Path path) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(path.toFile()))) {
            StringBuilder builder = new StringBuilder();
            String currentLine = reader.readLine();
            while (currentLine != null) {
                builder.append(currentLine);
                currentLine = reader.readLine();
            }

            return builder.toString();
        }
    }

    private void checkIfCreatedResponse(Response response) {
        int status = response.getStatus();
        if (status != 201) {
            LOGGER.error(String.format("Http Response Code was not 201 when attempting resource creation, but %s", status));
        }
    }

    // ==================== Upgrade (Structural) Flow ====================

    private ImportPreview previewUpgrade(InputStream zippedAgentConfigFiles, String targetAgentId) {
        // Created before the try so the finally can remove it even when unzip itself
        // fails: ZipResourceSource.close() is the only other thing that deletes the
        // tree, and an unzip that throws never reaches it.
        File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
        try {
            // try-with-resources: ZipResourceSource.close() removes the unzipped tree
            try (var source = new ZipResourceSource(targetDir.toPath(), jsonSerialization)) {
                this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);
                return structuralMatcher.buildPreview(source, targetAgentId, true);
            }
        } catch (WebApplicationException e) {
            // A missing or unreadable target agent is a 404, not a server fault.
            throw e;
        } catch (Exception e) {
            LOGGER.error("Upgrade preview failed: " + e.getMessage(), e);
            throw new InternalServerErrorException("Upgrade preview failed: " + e.getMessage(), e);
        } finally {
            deleteTempDirectoryQuietly(targetDir.toPath());
        }
    }

    private Response executeUpgradeFromZip(InputStream zippedAgentConfigFiles, String targetAgentId,
                                           String selectedOriginIds, String workflowOrderString) {
        // See previewUpgrade: the tree must be removable even if unzip throws.
        File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
        try {
            // try-with-resources: ZipResourceSource.close() removes the unzipped tree
            try (var source = new ZipResourceSource(targetDir.toPath(), jsonSerialization)) {
                this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);
                Set<String> selectedSet = parseSelectedResources(selectedOriginIds);
                List<String> workflowOrder = parseWorkflowOrder(workflowOrderString);

                return upgradeResponse(upgradeExecutor.executeUpgrade(source, targetAgentId, selectedSet, workflowOrder));
            }
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("Upgrade from ZIP failed: " + e.getMessage(), e);
            throw new InternalServerErrorException("Upgrade failed: " + e.getMessage(), e);
        } finally {
            deleteTempDirectoryQuietly(targetDir.toPath());
        }
    }

    /**
     * Turns an upgrade outcome into a response the caller can act on.
     * <ul>
     * <li>207 Multi-Status - some resources failed; the body lists them. Every
     * upgrade used to answer 201 regardless, so a half-applied sync looked
     * identical to a clean one.</li>
     * <li>201 Created - everything landed and something was written.</li>
     * <li>200 OK - source and target were already identical; nothing was written
     * and no agent version was burned.</li>
     * </ul>
     * The {@code Location} header is kept on every one of them, so clients that
     * only read the header keep working.
     */
    private Response upgradeResponse(UpgradeResult result) {
        Response.ResponseBuilder builder;
        if (result.hasFailures()) {
            LOGGER.warnf("Upgrade of %s completed with %d failed resource(s)",
                    result.agentUri(), result.failures().size());
            builder = Response.status(207, "Multi-Status");
        } else {
            builder = Response.status(result.wroteAnything() ? Response.Status.CREATED : Response.Status.OK);
        }

        // Use a manual Location header instead of Response.created(URI): the latter
        // validates the URI scheme and may strip eddi:// URIs.
        if (result.agentUri() != null) {
            builder.header("Location", result.agentUri().toString());
        }
        return builder.entity(result).type(MediaType.APPLICATION_JSON).build();
    }

    private List<String> parseWorkflowOrder(String workflowOrderString) {
        if (isNullOrEmpty(workflowOrderString))
            return null;
        return Arrays.stream(workflowOrderString.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // ==================== Live Sync Endpoints ====================

    /**
     * In development mode, allow HTTP for remote sync (easier local testing). In
     * production, enforce HTTPS to prevent credential leakage.
     * <p>
     * Read from the launch mode, not from the {@code quarkus.profile} <em>system
     * property</em>: that property is only set when someone passed
     * {@code -Dquarkus.profile=...} on the command line, so a container started the
     * normal way with {@code QUARKUS_PROFILE=dev} looked like production and
     * rejected every {@code http://} source with a message pointing at production
     * configuration.
     */
    boolean isDevMode() {
        LaunchMode mode = LaunchMode.current();
        return mode == LaunchMode.DEVELOPMENT || mode == LaunchMode.TEST;
    }

    private void validateSourceUrl(String sourceUrl) {
        SourceUrlValidator.validate(sourceUrl, isDevMode());
    }

    @Override
    public List<DocumentDescriptor> listRemoteAgents(String sourceUrl, String sourceAuth) {
        validateSourceUrl(sourceUrl);
        try {
            return RemoteApiResourceSource.listRemoteAgentDescriptors(sourceUrl, sourceAuth, jsonSerialization);
        } catch (Exception e) {
            LOGGER.errorf("Failed to list remote agents from %s: %s", sourceUrl, e.getMessage());
            throw new InternalServerErrorException("Failed to connect to remote instance: " + e.getMessage(), e);
        }
    }

    @Override
    public ImportPreview previewSync(String sourceUrl, String sourceAgentId, Integer sourceVersion,
                                     String targetAgentId, String sourceAuth) {
        validateSourceUrl(sourceUrl);
        try (var source = new RemoteApiResourceSource(sourceUrl, sourceAgentId, sourceVersion, sourceAuth, jsonSerialization)) {
            return structuralMatcher.buildPreview(source, targetAgentId, true);
        } catch (WebApplicationException e) {
            // An unreadable target agent is a 404 the operator can act on.
            throw e;
        } catch (Exception e) {
            LOGGER.errorf(e, "Sync preview failed for agent %s from %s", sourceAgentId, sourceUrl);
            throw new InternalServerErrorException("Sync preview failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ImportPreview> previewSyncBatch(String sourceUrl, List<SyncMapping> mappings, String sourceAuth) {
        validateSourceUrl(sourceUrl);
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }

        List<ImportPreview> previews = new ArrayList<>();
        for (SyncMapping mapping : mappings) {
            try (var source = new RemoteApiResourceSource(
                    sourceUrl, mapping.sourceAgentId(), mapping.sourceAgentVersion(),
                    sourceAuth, jsonSerialization)) {
                ImportPreview preview = structuralMatcher.buildPreview(source, mapping.targetAgentId(), true);
                previews.add(preview);
            } catch (Exception e) {
                LOGGER.warnf(e, "Batch preview failed for agent %s", mapping.sourceAgentId());
                // Report the failure in its own field. Prefixing the agent NAME with
                // "Error: " made a client string-match to tell a failed row from a
                // successful one, and rendered a remote exception where a name belongs.
                previews.add(new ImportPreview(
                        mapping.sourceAgentId(), null,
                        mapping.targetAgentId(), null, List.of(), e.getMessage()));
            }
        }
        return previews;
    }

    @Override
    public Response executeSync(String sourceUrl, String sourceAgentId, Integer sourceVersion,
                                String targetAgentId, String selectedResources, String workflowOrder,
                                String sourceAuth) {
        validateSourceUrl(sourceUrl);
        try (var source = new RemoteApiResourceSource(
                sourceUrl, sourceAgentId, sourceVersion, sourceAuth, jsonSerialization)) {
            Set<String> selectedSet = parseSelectedResources(selectedResources);
            List<String> wfOrder = parseWorkflowOrder(workflowOrder);

            return upgradeResponse(upgradeExecutor.executeUpgrade(source, targetAgentId, selectedSet, wfOrder));
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.errorf(e, "Sync execution failed for agent %s from %s", sourceAgentId, sourceUrl);
            throw new InternalServerErrorException("Sync failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Response executeSyncBatch(String sourceUrl, List<SyncRequest> requests, String sourceAuth) {
        validateSourceUrl(sourceUrl);
        if (requests == null || requests.isEmpty()) {
            return Response.ok(List.of()).build();
        }

        // One entry per request, in request order, whether it succeeded or not. The
        // endpoint used to answer 200 with a list of the URIs that happened to work,
        // so a batch in which every single agent failed was indistinguishable from a
        // batch with nothing to do.
        List<BatchSyncResult> results = new ArrayList<>();
        int failed = 0;
        for (SyncRequest request : requests) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InternalServerErrorException("Batch sync was interrupted after "
                        + results.size() + " of " + requests.size() + " agent(s).");
            }
            try (var source = new RemoteApiResourceSource(
                    sourceUrl, request.sourceAgentId(), request.sourceAgentVersion(),
                    sourceAuth, jsonSerialization)) {
                UpgradeResult result = upgradeExecutor.executeUpgrade(
                        source, request.targetAgentId(),
                        request.selectedResources(), request.workflowOrder());
                if (result.hasFailures()) {
                    failed++;
                }
                results.add(new BatchSyncResult(request.sourceAgentId(), request.targetAgentId(), result, null));
            } catch (Exception e) {
                LOGGER.warnf(e, "Batch sync failed for agent %s to %s",
                        request.sourceAgentId(), request.targetAgentId());
                // Continue with the remaining agents, but keep the failure visible.
                failed++;
                results.add(new BatchSyncResult(request.sourceAgentId(), request.targetAgentId(), null, e.getMessage()));
            }
        }

        if (failed == results.size()) {
            LOGGER.errorf("Batch sync failed for all %d agent(s) from %s", failed, sourceUrl);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(results).type(MediaType.APPLICATION_JSON).build();
        }
        if (failed > 0) {
            return Response.status(207, "Multi-Status").entity(results).type(MediaType.APPLICATION_JSON).build();
        }
        return Response.ok(results).type(MediaType.APPLICATION_JSON).build();
    }

    /**
     * One agent's outcome inside a batch sync.
     *
     * @param sourceAgentId
     *            the agent that was read from the remote instance
     * @param targetAgentId
     *            the local agent it was synced into
     * @param result
     *            what the upgrade did, or null when it could not run at all
     * @param error
     *            why it could not run, or null on success
     */
    public record BatchSyncResult(String sourceAgentId, String targetAgentId, UpgradeResult result, String error) {
    }

}
