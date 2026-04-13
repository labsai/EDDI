package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IRestImportService;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.backup.model.SyncRequest;
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
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
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
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.FileUtilities;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.createDocumentDescriptor;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static ai.labs.eddi.engine.model.Deployment.Environment.production;
import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestImportService extends AbstractBackupService implements IRestImportService {
    private static final Pattern EDDI_URI_PATTERN = Pattern.compile("\"eddi://ai.labs..*?\"");
    private static final String AGENT_FILE_ENDING = ".agent.json";
    private static final String DESCRIPTOR_FILE_ENDING = ".descriptor.json";
    private static final String STRATEGY_MERGE = "merge";

    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp", "import"));
    private final IZipArchive zipArchive;
    private final IJsonSerialization jsonSerialization;

    private final IRestAgentAdministration restAgentAdministration;
    private final IMigrationManager migrationManager;
    private final IDeploymentListener deploymentListener;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final TemplateSyntaxMigrator templateSyntaxMigrator;
    private final StructuralMatcher structuralMatcher;
    private final UpgradeExecutor upgradeExecutor;

    private static final Logger log = Logger.getLogger(RestImportService.class);

    @Inject
    public RestImportService(IZipArchive zipArchive, IJsonSerialization jsonSerialization,
            IRestAgentAdministration restAgentAdministration, IMigrationManager migrationManager, IDeploymentListener deploymentListener,
            IDocumentDescriptorStore documentDescriptorStore, TemplateSyntaxMigrator templateSyntaxMigrator,
            StructuralMatcher structuralMatcher, UpgradeExecutor upgradeExecutor) {
        this.zipArchive = zipArchive;
        this.jsonSerialization = jsonSerialization;
        this.restAgentAdministration = restAgentAdministration;
        this.migrationManager = migrationManager;
        this.deploymentListener = deploymentListener;
        this.documentDescriptorStore = documentDescriptorStore;
        this.templateSyntaxMigrator = templateSyntaxMigrator;
        this.structuralMatcher = structuralMatcher;
        this.upgradeExecutor = upgradeExecutor;
    }

    @Override
    public List<AgentDeploymentStatus> importInitialAgents() {
        try {
            var agentExampleFiles = getResourceFiles("/initial-agents/available_agents.txt");
            List<CompletableFuture<Void>> deploymentFutures = new ArrayList<>();

            for (var agentFileName : agentExampleFiles) {
                Response result = importAgent(getResourceAsStream("/initial-agents/" + agentFileName), "create", null, null, null);
                if (result != null && result.getStatus() == 201) {
                    String resourceUri = result.getHeaderString("Location");
                    if (resourceUri != null && !resourceUri.isBlank()) {
                        var agentId = RestUtilities.extractResourceId(URI.create(resourceUri));
                        if (agentId != null) {
                            var deploymentFuture = deploymentListener.registerAgentDeployment(agentId.getId(), agentId.getVersion());
                            deploymentFutures.add(deploymentFuture);

                            restAgentAdministration.deployAgent(production, agentId.getId(), agentId.getVersion(), true, false);
                        }
                    }
                }
            }

            // Wait for all deployments to complete
            CompletableFuture.allOf(deploymentFutures.toArray(new CompletableFuture[0])).join();

            log.info("Imported & Deployed Initial Agents");
            return restAgentAdministration.getDeploymentStatuses(production);
        } catch (IOException e) {
            throw sneakyThrow(e);
        }
    }

    private List<String> getResourceFiles(String path) throws IOException {
        List<String> filenames = new ArrayList<>();

        try (var in = getResourceAsStream(path); var br = new BufferedReader(new InputStreamReader(in))) {

            String resource;
            while ((resource = br.readLine()) != null) {
                filenames.add(resource);
            }
        }

        return filenames;
    }

    // ==================== Preview ====================

    @Override
    public ImportPreview previewImport(InputStream zippedAgentConfigFiles, String targetAgentId) {
        // When targetAgentId is provided, use the new structural matching pipeline
        if (targetAgentId != null && !targetAgentId.isBlank()) {
            return previewUpgrade(zippedAgentConfigFiles, targetAgentId);
        }
        // Legacy merge preview path
        try {
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
            this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);
            var targetDirPath = targetDir.getPath();

            try (var directoryStream = Files.newDirectoryStream(Paths.get(targetDirPath), path -> path.toString().endsWith(AGENT_FILE_ENDING))) {

                for (Path agentFilePath : directoryStream) {
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

                    return new ImportPreview(agentOriginId, agentName, null, null, diffs);
                }
            }

            return new ImportPreview(null, null, null, null, List.of());
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Preview failed: " + e.getMessage(), e);
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
                        log.debugf("Could not preview snippet %s: %s", snippetFilePath.getFileName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debugf("Could not scan snippets for preview: %s", e.getMessage());
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
            log.debug("Could not look up origin ID " + originId + ": " + e.getMessage());
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

    private String extractIdFromAgentFilename(Path agentFilePath) {
        String filename = agentFilePath.getFileName().toString();
        return filename.substring(0, filename.length() - AGENT_FILE_ENDING.length());
    }

    // ==================== Import ====================

    @Override
    public Response importAgent(InputStream zippedAgentConfigFiles, String strategy, String selectedOriginIds,
                                String targetAgentId, String workflowOrder) {
        try {
            // "upgrade" strategy → use the new structural matcher + upgrade executor
            if ("upgrade".equalsIgnoreCase(strategy) && targetAgentId != null) {
                return executeUpgradeFromZip(zippedAgentConfigFiles, targetAgentId, selectedOriginIds, workflowOrder);
            }
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));

            Set<String> selectedSet = parseSelectedResources(selectedOriginIds);
            boolean isMerge = STRATEGY_MERGE.equalsIgnoreCase(strategy);

            return importAgentZipFile(zippedAgentConfigFiles, targetDir, isMerge, selectedSet);
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
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

    private Response importAgentZipFile(InputStream zippedAgentConfigFiles, File targetDir, boolean isMerge,
                                        Set<String> selectedSet)
            throws IOException {

        this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);
        var targetDirPath = targetDir.getPath();

        // Import snippets (global resources, not workflow-embedded)
        importSnippets(Paths.get(targetDirPath), isMerge);

        URI lastAgentUri = null;
        try (var directoryStream = Files.newDirectoryStream(Paths.get(targetDirPath), path -> path.toString().endsWith(AGENT_FILE_ENDING))) {
            for (var agentFilePath : directoryStream) {
                try {
                    String agentOriginId = extractIdFromAgentFilename(agentFilePath);
                    String agentFileString = readFile(agentFilePath);

                    // Normalize legacy eddi:// URIs from v5 ZIP exports to v6 canonical form
                    agentFileString = normalizeLegacyUris(agentFileString);

                    AgentConfiguration agentConfig = jsonSerialization.deserialize(agentFileString, AgentConfiguration.class);
                    agentConfig.getWorkflows()
                            .forEach(workflowUri -> parseWorkflow(targetDirPath, workflowUri, agentConfig, isMerge, selectedSet));

                    URI newAgentUri;
                    if (isMerge && isSelected(selectedSet, agentOriginId)) {
                        newAgentUri = createOrUpdateAgent(agentConfig, agentOriginId);
                    } else {
                        newAgentUri = createNewAgent(agentConfig);
                    }

                    updateDocumentDescriptor(Paths.get(targetDirPath), buildOldAgentUri(agentFilePath), newAgentUri);

                    // Set originId on the new agent's descriptor
                    setOriginIdOnDescriptor(newAgentUri, agentOriginId);

                    lastAgentUri = newAgentUri;
                } catch (IOException | RestInterfaceFactory.RestInterfaceFactoryException e) {
                    log.error(e.getLocalizedMessage(), e);
                    throw new InternalServerErrorException(e.getLocalizedMessage(), e);
                }
            }
        }
        log.infof("Import complete: lastAgentUri=%s", lastAgentUri);
        if (lastAgentUri != null) {
            // Use manual .header("Location", ...) instead of Response.created(URI)
            // because Response.created() validates the URI scheme and may strip eddi://
            // URIs
            return Response.status(Response.Status.CREATED)
                    .header("Location", lastAgentUri.toString()).build();
        }
        return Response.ok(Map.of("resourceUri", "")).build();
    }

    private URI buildOldAgentUri(Path agentPath) {
        String agentPathString = agentPath.toString();
        String oldAgentId = agentPathString.substring(agentPathString.lastIndexOf(File.separator) + 1,
                agentPathString.lastIndexOf(AGENT_FILE_ENDING));

        return URI.create(IRestAgentStore.resourceURI + oldAgentId + IRestAgentStore.versionQueryParam + "1");
    }

    private void parseWorkflow(String targetDirPath, URI workflowUri, AgentConfiguration agentConfig, boolean isMerge,
                               Set<String> selectedSet) {
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

                        // loading old resources, creating/updating them,
                        // updating document descriptor and replacing references in workflow config

                        // ... for dictionaries
                        List<URI> dictionaryUris = extractResourcesUris(workflowFileString, DICTIONARY_URI_PATTERN);
                        List<URI> newDictionaryUris = createOrUpdateResources(
                                readResources(dictionaryUris, workflowPath, DICTIONARY_EXT, DictionaryConfiguration.class), dictionaryUris, isMerge,
                                selectedSet, this::createNewDictionaries, this::updateDictionary);

                        updateDocumentDescriptor(workflowPath, dictionaryUris, newDictionaryUris);
                        workflowFileString = replaceURIs(workflowFileString, dictionaryUris, newDictionaryUris);

                        // ... for behavior
                        List<URI> behaviorUris = extractResourcesUris(workflowFileString, BEHAVIOR_URI_PATTERN);
                        List<URI> newBehaviorUris = createOrUpdateResources(
                                readResources(behaviorUris, workflowPath, BEHAVIOR_EXT, RuleSetConfiguration.class), behaviorUris, isMerge,
                                selectedSet, this::createNewBehaviors, this::updateBehavior);

                        updateDocumentDescriptor(workflowPath, behaviorUris, newBehaviorUris);
                        workflowFileString = replaceURIs(workflowFileString, behaviorUris, newBehaviorUris);

                        // ... for http calls
                        List<URI> httpCallsUris = extractResourcesUris(workflowFileString, HTTPCALLS_URI_PATTERN);
                        List<URI> newApiCallsUris = createOrUpdateResources(
                                readResources(httpCallsUris, workflowPath, HTTPCALLS_EXT, ApiCallsConfiguration.class), httpCallsUris, isMerge,
                                selectedSet, this::createNewApiCalls, this::updateApiCalls);

                        updateDocumentDescriptor(workflowPath, httpCallsUris, newApiCallsUris);
                        workflowFileString = replaceURIs(workflowFileString, httpCallsUris, newApiCallsUris);

                        // ... for langchain
                        List<URI> langchainUris = extractResourcesUris(workflowFileString, LANGCHAIN_URI_PATTERN);
                        List<URI> newLangchainUris = createOrUpdateResources(
                                readResources(langchainUris, workflowPath, LLM_EXT, LlmConfiguration.class), langchainUris, isMerge, selectedSet,
                                this::createNewLlm, this::updateLangchain);

                        updateDocumentDescriptor(workflowPath, langchainUris, newLangchainUris);
                        workflowFileString = replaceURIs(workflowFileString, langchainUris, newLangchainUris);

                        // ... for property
                        List<URI> propertyUris = extractResourcesUris(workflowFileString, PROPERTY_URI_PATTERN);
                        List<URI> newPropertyUris = createOrUpdateResources(
                                readResources(propertyUris, workflowPath, PROPERTY_EXT, PropertySetterConfiguration.class), propertyUris, isMerge,
                                selectedSet, this::createNewProperties, this::updateProperty);

                        updateDocumentDescriptor(workflowPath, propertyUris, newPropertyUris);
                        workflowFileString = replaceURIs(workflowFileString, propertyUris, newPropertyUris);

                        // ... for output
                        List<URI> outputUris = extractResourcesUris(workflowFileString, OUTPUT_URI_PATTERN);
                        List<URI> newOutputUris = createOrUpdateResources(
                                readResources(outputUris, workflowPath, OUTPUT_EXT, OutputConfigurationSet.class), outputUris, isMerge, selectedSet,
                                this::createNewOutputs, this::updateOutput);

                        updateDocumentDescriptor(workflowPath, outputUris, newOutputUris);
                        workflowFileString = replaceURIs(workflowFileString, outputUris, newOutputUris);

                        // ... for mcp calls
                        List<URI> mcpCallsUris = extractResourcesUris(workflowFileString, MCPCALLS_URI_PATTERN);
                        List<URI> newMcpCallsUris = createOrUpdateResources(
                                readResources(mcpCallsUris, workflowPath, MCPCALLS_EXT, McpCallsConfiguration.class), mcpCallsUris, isMerge,
                                selectedSet, this::createNewMcpCalls, this::updateMcpCalls);

                        updateDocumentDescriptor(workflowPath, mcpCallsUris, newMcpCallsUris);
                        workflowFileString = replaceURIs(workflowFileString, mcpCallsUris, newMcpCallsUris);

                        // ... for rag
                        List<URI> ragUris = extractResourcesUris(workflowFileString, RAG_URI_PATTERN);
                        List<URI> newRagUris = createOrUpdateResources(
                                readResources(ragUris, workflowPath, RAG_EXT, RagConfiguration.class), ragUris, isMerge, selectedSet,
                                this::createNewRags, this::updateRag);

                        updateDocumentDescriptor(workflowPath, ragUris, newRagUris);
                        workflowFileString = replaceURIs(workflowFileString, ragUris, newRagUris);

                        // creating updated workflow and replacing references in Agent config
                        URI newWorkflowUri;
                        if (isMerge && isSelected(selectedSet, workflowId)) {
                            newWorkflowUri = createOrUpdateWorkflow(workflowFileString, workflowId);
                        } else {
                            newWorkflowUri = createNewWorkflow(workflowFileString);
                        }

                        // Set originId on the workflow's descriptor
                        setOriginIdOnDescriptor(newWorkflowUri, workflowId);

                        updateDocumentDescriptor(workflowPath, workflowUri, newWorkflowUri);
                        agentConfig.setWorkflows(agentConfig.getWorkflows().stream().map(uri -> uri.equals(workflowUri) ? newWorkflowUri : uri)
                                .collect(Collectors.toList()));

                    } catch (IOException | RestInterfaceFactory.RestInterfaceFactoryException | CallbackMatcher.CallbackMatcherException e) {
                        log.error(e.getLocalizedMessage(), e);
                        throw new InternalServerErrorException(e.getMessage(), e);
                    }
                });

            }

        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        }
    }

    // ==================== Create or Update Logic ====================

    @FunctionalInterface
    private interface ResourceCreator<T> {
        List<URI> create(List<T> configs) throws RestInterfaceFactory.RestInterfaceFactoryException;
    }

    @FunctionalInterface
    private interface ResourceUpdater<T> {
        URI update(T config, String localId, Integer localVersion) throws RestInterfaceFactory.RestInterfaceFactoryException;
    }

    private <T> List<URI> createOrUpdateResources(List<T> configs, List<URI> originUris, boolean isMerge, Set<String> selectedSet,
                                                  ResourceCreator<T> creator, ResourceUpdater<T> updater)
            throws RestInterfaceFactory.RestInterfaceFactoryException {

        if (!isMerge) {
            // Original behavior: create everything new
            List<URI> newUris = creator.create(configs);
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
                // Not selected — check if local exists, keep it; otherwise create
                URI existingUri = findLocalUriByOriginId(originId);
                resultUris.add(existingUri != null ? existingUri : originUris.get(i));
                continue;
            }

            // Try to find existing local resource
            URI existingUri = findLocalUriByOriginId(originId);
            if (existingUri != null) {
                // Update existing
                IResourceId localResId = RestUtilities.extractResourceId(existingUri);
                if (localResId != null) {
                    URI updatedUri = updater.update(config, localResId.getId(), localResId.getVersion());
                    setOriginIdOnDescriptor(updatedUri, originId);
                    resultUris.add(updatedUri);
                    continue;
                }
            }

            // No existing resource found — create new
            List<URI> created = creator.create(List.of(config));
            if (!created.isEmpty()) {
                setOriginIdOnDescriptor(created.getFirst(), originId);
                resultUris.add(created.getFirst());
            }
        }
        return resultUris;
    }

    private URI findLocalUriByOriginId(String originId) {
        try {
            List<DocumentDescriptor> existing = documentDescriptorStore.findByOriginId(originId);
            if (!existing.isEmpty()) {
                return existing.getFirst().getResource();
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            log.debug("Could not look up origin ID " + originId + ": " + e.getMessage());
        }
        return null;
    }

    private URI createOrUpdateAgent(AgentConfiguration agentConfiguration, String agentOriginId)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
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
        return createNewAgent(agentConfiguration);
    }

    private URI createOrUpdateWorkflow(String workflowFileString, String workflowOriginId)
            throws RestInterfaceFactory.RestInterfaceFactoryException, IOException {
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
        return createNewWorkflow(workflowFileString);
    }

    private void setOriginIdOnDescriptor(URI resourceUri, String originId) {
        try {
            IResourceId resourceId = RestUtilities.extractResourceId(resourceUri);
            if (resourceId != null) {
                DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion());
                if (descriptor != null && !originId.equals(descriptor.getOriginId())) {
                    descriptor.setOriginId(originId);
                    // Use setDescriptor directly — patchDescriptor only patches name/description
                    documentDescriptorStore.setDescriptor(resourceId.getId(), resourceId.getVersion(), descriptor);
                }
            }
        } catch (Exception e) {
            log.warn("Could not set originId on descriptor for " + resourceUri + ": " + e.getMessage());
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
     */
    @SuppressWarnings("unchecked")
    private <T> URI createResourceDirect(Class<?> storeClass, T document, String resourceUri) {
        try {
            IResourceStore<T> store = (IResourceStore<T>) jakarta.enterprise.inject.spi.CDI.current().select(storeClass).get();
            IResourceId resourceId = store.create(document);
            URI createdUri = RestUtilities.createURI(resourceUri, resourceId.getId(), IRestVersionInfo.versionQueryParam, resourceId.getVersion());

            // Create the DocumentDescriptor that the DocumentDescriptorFilter would
            // normally create on a 201 response. Since we bypass the REST layer,
            // the filter never runs, so we must create it manually.
            documentDescriptorStore.createDescriptor(
                    resourceId.getId(), resourceId.getVersion(), createDocumentDescriptor(createdUri));

            return createdUri;
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    private URI createNewAgent(AgentConfiguration agentConfiguration) {
        return createResourceDirect(IAgentStore.class, agentConfiguration, IRestAgentStore.resourceURI);
    }

    private URI createNewWorkflow(String workflowFileString) throws IOException {
        WorkflowConfiguration workflowConfig = jsonSerialization.deserialize(workflowFileString, WorkflowConfiguration.class);
        return createResourceDirect(IWorkflowStore.class, workflowConfig, IRestWorkflowStore.resourceURI);
    }

    private List<URI> createNewDictionaries(List<DictionaryConfiguration> configs) {
        return configs.stream().map(c -> createResourceDirect(IDictionaryStore.class, c, IRestDictionaryStore.resourceURI)).toList();
    }

    private List<URI> createNewBehaviors(List<RuleSetConfiguration> configs) {
        return configs.stream().map(c -> createResourceDirect(IRuleSetStore.class, c, IRestRuleSetStore.resourceURI)).toList();
    }

    private List<URI> createNewApiCalls(List<ApiCallsConfiguration> configs) {
        return configs.stream().map(c -> createResourceDirect(IApiCallsStore.class, c, IRestApiCallsStore.resourceURI)).toList();
    }

    private List<URI> createNewLlm(List<LlmConfiguration> configs) {
        return configs.stream().map(c -> createResourceDirect(ILlmStore.class, c, IRestLlmStore.resourceURI)).toList();
    }

    private List<URI> createNewProperties(List<PropertySetterConfiguration> configs) {
        return configs.stream().map(c -> createResourceDirect(IPropertySetterStore.class, c, IRestPropertySetterStore.resourceURI)).toList();
    }

    private List<URI> createNewOutputs(List<OutputConfigurationSet> configs) {
        return configs.stream().map(c -> createResourceDirect(IOutputStore.class, c, IRestOutputStore.resourceURI)).toList();
    }

    // ==================== Resource Update (merge logic) ====================

    private URI updateDictionary(DictionaryConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestDictionaryStore store = getRestResourceStore(IRestDictionaryStore.class);
        Response response = store.updateRegularDictionary(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestDictionaryStore.resourceURI + localId + IRestDictionaryStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IDictionaryStore.class, config, IRestDictionaryStore.resourceURI);
    }

    private URI updateBehavior(RuleSetConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestRuleSetStore store = getRestResourceStore(IRestRuleSetStore.class);
        Response response = store.updateRuleSet(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestRuleSetStore.resourceURI + localId + IRestRuleSetStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IRuleSetStore.class, config, IRestRuleSetStore.resourceURI);
    }

    private URI updateApiCalls(ApiCallsConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestApiCallsStore store = getRestResourceStore(IRestApiCallsStore.class);
        Response response = store.updateApiCalls(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestApiCallsStore.resourceURI + localId + IRestApiCallsStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IApiCallsStore.class, config, IRestApiCallsStore.resourceURI);
    }

    private URI updateLangchain(LlmConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestLlmStore store = getRestResourceStore(IRestLlmStore.class);
        Response response = store.updateLlm(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestLlmStore.resourceURI + localId + IRestLlmStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(ILlmStore.class, config, IRestLlmStore.resourceURI);
    }

    private URI updateProperty(PropertySetterConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestPropertySetterStore store = getRestResourceStore(IRestPropertySetterStore.class);
        Response response = store.updatePropertySetter(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestPropertySetterStore.resourceURI + localId + IRestPropertySetterStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IPropertySetterStore.class, config, IRestPropertySetterStore.resourceURI);
    }

    private URI updateOutput(OutputConfigurationSet config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestOutputStore store = getRestResourceStore(IRestOutputStore.class);
        Response response = store.updateOutputSet(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestOutputStore.resourceURI + localId + IRestOutputStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IOutputStore.class, config, IRestOutputStore.resourceURI);
    }

    private List<URI> createNewMcpCalls(List<McpCallsConfiguration> configs) {
        return configs.stream().map(c -> createResourceDirect(IMcpCallsStore.class, c, IRestMcpCallsStore.resourceURI)).toList();
    }

    private URI updateMcpCalls(McpCallsConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestMcpCallsStore store = getRestResourceStore(IRestMcpCallsStore.class);
        Response response = store.updateMcpCalls(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestMcpCallsStore.resourceURI + localId + IRestMcpCallsStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IMcpCallsStore.class, config, IRestMcpCallsStore.resourceURI);
    }

    private List<URI> createNewRags(List<RagConfiguration> configs) {
        return configs.stream().map(c -> createResourceDirect(IRagStore.class, c, IRestRagStore.resourceURI)).toList();
    }

    private URI updateRag(RagConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestRagStore store = getRestResourceStore(IRestRagStore.class);
        Response response = store.updateRag(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestRagStore.resourceURI + localId + IRestRagStore.versionQueryParam + (localVersion + 1));
        }
        return createResourceDirect(IRagStore.class, config, IRestRagStore.resourceURI);
    }

    // ==================== Snippet Import ====================

    private void importSnippets(Path targetDirPath, boolean isMerge) {
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
                                    log.debugf("Updated existing snippet '%s' (id=%s, v=%d)",
                                            snippetName, localResId.getId(), localResId.getVersion());
                                    importedCount++;
                                    continue;
                                }
                                // Update failed (e.g., version conflict) — fall through to create
                                log.warnf("Update failed for snippet '%s' (status=%d), creating new",
                                        snippetName, updateResp.getStatus());
                            } else {
                                // Create strategy: snippet already exists globally, skip to avoid duplicates
                                log.debugf("Snippet '%s' already exists, skipping (create strategy)", snippetName);
                                skippedCount++;
                                continue;
                            }
                        }

                        // Create new snippet
                        Response createResp = restSnippetStore.createSnippet(snippet);
                        checkIfCreatedResponse(createResp);
                        importedCount++;
                        log.debugf("Created new snippet '%s'", snippetName);
                    } catch (Exception e) {
                        log.warnf("Failed to import snippet from %s: %s", snippetFilePath, e.getMessage());
                    }
                }
                if (importedCount > 0 || skippedCount > 0) {
                    log.infof("Snippets: imported %d, skipped %d (already exist)", importedCount, skippedCount);
                }
            }
        } catch (Exception e) {
            log.warnf("Failed to import snippets: %s", e.getMessage());
        }
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
                    log.debugf("Could not load snippet for dedup: %s", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warnf("Could not build snippet name map: %s", e.getMessage());
        }
        return nameMap;
    }

    private Path findSnippetsDir(Path targetDirPath) {
        // Check directly under target dir
        Path direct = Paths.get(targetDirPath.toString(), "snippets");
        if (Files.exists(direct)) {
            return direct;
        }

        // Check inside agent subdirectories (the ZIP structure nests under
        // agentId/version/)
        try (var dirStream = Files.newDirectoryStream(targetDirPath, Files::isDirectory)) {
            for (Path subDir : dirStream) {
                // Look in agentId/ directory
                Path nested = Paths.get(subDir.toString(), "snippets");
                if (Files.exists(nested)) {
                    return nested;
                }
                // Look in agentId/version/ directories
                try (var versionStream = Files.newDirectoryStream(subDir, Files::isDirectory)) {
                    for (Path versionDir : versionStream) {
                        Path deepNested = Paths.get(versionDir.toString(), "snippets");
                        if (Files.exists(deepNested)) {
                            return deepNested;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Error searching for snippets directory: " + e.getMessage());
        }
        return null;
    }

    // ==================== Shared helpers ====================

    private void updateDocumentDescriptor(Path directoryPath, URI oldUri, URI newUri) throws RestInterfaceFactory.RestInterfaceFactoryException {
        updateDocumentDescriptor(directoryPath, Collections.singletonList(oldUri), Collections.singletonList(newUri));
    }

    private void updateDocumentDescriptor(Path directoryPath, List<URI> oldUris, List<URI> newUris)
            throws RestInterfaceFactory.RestInterfaceFactoryException {

        IRestDocumentDescriptorStore restDocumentDescriptorStore = getRestResourceStore(IRestDocumentDescriptorStore.class);
        IntStream.range(0, oldUris.size()).forEach(idx -> {
            try {
                URI oldUri = oldUris.get(idx);
                IResourceId oldResourceId = RestUtilities.extractResourceId(oldUri);
                if (oldResourceId != null) {
                    var oldDocumentDescriptor = readDocumentDescriptorFromFile(directoryPath, oldResourceId);

                    URI newUri = newUris.get(idx);
                    IResourceId newResourceId = RestUtilities.extractResourceId(newUri);

                    if (newResourceId != null) {
                        // Update the resource URI to point to the new location
                        // (the old descriptor from the ZIP file still has the old URI)
                        oldDocumentDescriptor.setResource(newUri);

                        PatchInstruction<DocumentDescriptor> patchInstruction = new PatchInstruction<>();
                        patchInstruction.setOperation(PatchInstruction.PatchOperation.SET);
                        patchInstruction.setDocument(oldDocumentDescriptor);

                        restDocumentDescriptorStore.patchDescriptor(newResourceId.getId(), newResourceId.getVersion(), patchInstruction);
                    }
                }
            } catch (IOException e) {
                log.error(e.getLocalizedMessage(), e);
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

    private <T> T getRestResourceStore(Class<T> clazz) throws RestInterfaceFactory.RestInterfaceFactoryException {
        // Use direct CDI lookup instead of HTTP loopback proxy.
        // The MP REST Client proxy strips response headers (Location, X-Resource-URI)
        // and runs on the Vert.x IO event loop, causing deadlocks during import.
        return jakarta.enterprise.inject.spi.CDI.current().select(clazz).get();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> readResources(List<URI> uris, Path workflowPath, String extension, Class<T> clazz) {
        return uris.stream().map(uri -> {
            Path resourcePath = null;
            String resourceContent = null;
            try {
                IResourceId resourceId = RestUtilities.extractResourceId(uri);
                if (resourceId == null) {
                    throw new IOException("resourceId was null");
                }
                resourceContent = readFile(createResourcePath(workflowPath, resourceId.getId(), extension));
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

                // Final pass: migrate any remaining Thymeleaf template syntax to Qute
                resourceContent = templateSyntaxMigrator.migrate(resourceContent);

                return jsonSerialization.deserialize(resourceContent, clazz);
            } catch (Exception e) {
                log.error(e.getLocalizedMessage());
                log.error(String.format("uri is: %s", uri));
                log.error(String.format("workflowPath is: %s", workflowPath));
                log.error(String.format("resourcePath is: %s", resourcePath));
                log.error(String.format("resourceContent is:\n%s", resourceContent));
                return null;
            }
        }).collect(Collectors.toList());
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
            log.error(String.format("Http Response Code was not 201 when attempting resource creation, but %s", status));
        }
    }

    // ==================== Upgrade (Structural) Flow ====================

    private ImportPreview previewUpgrade(InputStream zippedAgentConfigFiles, String targetAgentId) {
        try {
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
            this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);

            ZipResourceSource source = new ZipResourceSource(targetDir.toPath(), jsonSerialization);
            return structuralMatcher.buildPreview(source, targetAgentId, true);
        } catch (Exception e) {
            log.error("Upgrade preview failed: " + e.getMessage(), e);
            throw new InternalServerErrorException("Upgrade preview failed: " + e.getMessage(), e);
        }
    }

    private Response executeUpgradeFromZip(InputStream zippedAgentConfigFiles, String targetAgentId,
                                           String selectedOriginIds, String workflowOrderString) {
        try {
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
            this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);

            ZipResourceSource source = new ZipResourceSource(targetDir.toPath(), jsonSerialization);
            Set<String> selectedSet = parseSelectedResources(selectedOriginIds);
            List<String> workflowOrder = parseWorkflowOrder(workflowOrderString);

            URI resultUri = upgradeExecutor.executeUpgrade(source, targetAgentId, selectedSet, workflowOrder);
            return Response.status(Response.Status.CREATED)
                    .header("Location", resultUri.toString()).build();
        } catch (Exception e) {
            log.error("Upgrade from ZIP failed: " + e.getMessage(), e);
            throw new InternalServerErrorException("Upgrade failed: " + e.getMessage(), e);
        }
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
     */
    private boolean isDevMode() {
        String profile = System.getProperty("quarkus.profile", "prod");
        return "dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile);
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
            log.errorf("Failed to list remote agents from %s: %s", sourceUrl, e.getMessage());
            throw new InternalServerErrorException("Failed to connect to remote instance: " + e.getMessage(), e);
        }
    }

    @Override
    public ImportPreview previewSync(String sourceUrl, String sourceAgentId, Integer sourceVersion,
                                     String targetAgentId, String sourceAuth) {
        validateSourceUrl(sourceUrl);
        try (var source = new RemoteApiResourceSource(sourceUrl, sourceAgentId, sourceVersion, sourceAuth, jsonSerialization)) {
            return structuralMatcher.buildPreview(source, targetAgentId, true);
        } catch (Exception e) {
            log.errorf("Sync preview failed for agent %s from %s: %s", sourceAgentId, sourceUrl, e.getMessage());
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
                log.warnf("Batch preview failed for agent %s: %s", mapping.sourceAgentId(), e.getMessage());
                // Add a failed preview entry so the caller knows which agent failed
                previews.add(new ImportPreview(
                        mapping.sourceAgentId(), "Error: " + e.getMessage(),
                        mapping.targetAgentId(), null, List.of()));
            }
        }
        return previews;
    }

    @Override
    public Response executeSync(String sourceUrl, String sourceAgentId, Integer sourceVersion,
                                String targetAgentId, String selectedResources, String workflowOrder,
                                String sourceAuth) {
        validateSourceUrl(sourceUrl);
        try {
            try (var source = new RemoteApiResourceSource(
                    sourceUrl, sourceAgentId, sourceVersion, sourceAuth, jsonSerialization)) {
                Set<String> selectedSet = parseSelectedResources(selectedResources);
                List<String> wfOrder = parseWorkflowOrder(workflowOrder);

                URI resultUri = upgradeExecutor.executeUpgrade(source, targetAgentId, selectedSet, wfOrder);
                return Response.status(Response.Status.CREATED)
                        .header("Location", resultUri.toString()).build();
            }
        } catch (Exception e) {
            log.errorf("Sync execution failed for agent %s from %s: %s",
                    sourceAgentId, sourceUrl, e.getMessage());
            throw new InternalServerErrorException("Sync failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Response executeSyncBatch(String sourceUrl, List<SyncRequest> requests, String sourceAuth) {
        validateSourceUrl(sourceUrl);
        try {
            List<URI> resultUris = new ArrayList<>();
            for (SyncRequest request : requests) {
                try (var source = new RemoteApiResourceSource(
                        sourceUrl, request.sourceAgentId(), request.sourceAgentVersion(),
                        sourceAuth, jsonSerialization)) {
                    URI resultUri = upgradeExecutor.executeUpgrade(
                            source, request.targetAgentId(),
                            request.selectedResources(), request.workflowOrder());
                    resultUris.add(resultUri);
                } catch (Exception e) {
                    log.warnf("Batch sync failed for agent %s→%s: %s",
                            request.sourceAgentId(), request.targetAgentId(), e.getMessage());
                    // Continue with remaining agents — partial success is better than total failure
                }
            }

            return Response.ok(resultUris).build();
        } catch (Exception e) {
            log.errorf("Batch sync execution failed: %s", e.getMessage());
            throw new InternalServerErrorException("Batch sync failed: " + e.getMessage(), e);
        }
    }

}
