package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IRestImportService;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
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
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.IDeploymentListener;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.utils.FileUtilities;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestAgentAdministration restAgentAdministration;
    private final IMigrationManager migrationManager;
    private final IDeploymentListener deploymentListener;
    private final IDocumentDescriptorStore documentDescriptorStore;

    private static final Logger log = Logger.getLogger(RestImportService.class);

    @Inject
    public RestImportService(IZipArchive zipArchive,
            IJsonSerialization jsonSerialization,
            IRestInterfaceFactory restInterfaceFactory,
            IRestAgentAdministration restAgentAdministration,
            IMigrationManager migrationManager,
            IDeploymentListener deploymentListener,
            IDocumentDescriptorStore documentDescriptorStore) {
        this.zipArchive = zipArchive;
        this.jsonSerialization = jsonSerialization;
        this.restInterfaceFactory = restInterfaceFactory;
        this.restAgentAdministration = restAgentAdministration;
        this.migrationManager = migrationManager;
        this.deploymentListener = deploymentListener;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<AgentDeploymentStatus> importInitialAgents() {
        try {
            var agentExampleFiles = getResourceFiles("/initial-agents/available_agents.txt");
            List<CompletableFuture<Void>> deploymentFutures = new ArrayList<>();

            for (var agentFileName : agentExampleFiles) {
                importAgent(getResourceAsStream("/initial-agents/" + agentFileName),
                        "create", null,
                        new MockAsyncResponse() {
                            @Override
                            public boolean resume(Object responseObj) {
                                if (responseObj instanceof Response response) {
                                    var agentId = RestUtilities.extractResourceId(response.getLocation());
                                    if (agentId != null) {
                                        var deploymentFuture = deploymentListener
                                                .registerAgentDeployment(agentId.getId(), agentId.getVersion());
                                        deploymentFutures.add(deploymentFuture);

                                        restAgentAdministration.deployAgent(
                                                production, agentId.getId(), agentId.getVersion(), true, false);

                                        return true;
                                    }
                                }
                                return false;
                            }
                        });
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

        try (var in = getResourceAsStream(path);
                var br = new BufferedReader(new InputStreamReader(in))) {

            String resource;
            while ((resource = br.readLine()) != null) {
                filenames.add(resource);
            }
        }

        return filenames;
    }

    // ==================== Preview ====================

    @Override
    public ImportPreview previewImport(InputStream zippedAgentConfigFiles) {
        try {
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
            this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);
            var targetDirPath = targetDir.getPath();

            try (var directoryStream = Files.newDirectoryStream(Paths.get(targetDirPath),
                    path -> path.toString().endsWith(AGENT_FILE_ENDING))) {

                for (Path agentFilePath : directoryStream) {
                    String agentFileString = readFile(agentFilePath);
                    String agentOriginId = extractIdFromAgentFilename(agentFilePath);
                    String agentName = readNameFromDescriptor(Paths.get(targetDirPath), agentOriginId);

                    List<ResourceDiff> diffs = new ArrayList<>();

                    // Agent itself
                    diffs.add(buildResourceDiff(agentOriginId, "agent", agentName));

                    // Workflows & their extensions
                    AgentConfiguration agentConfig = jsonSerialization.deserialize(agentFileString,
                            AgentConfiguration.class);
                    for (URI workflowUri : agentConfig.getWorkflows()) {
                        IResourceId packageResourceId = RestUtilities.extractResourceId(workflowUri);
                        if (packageResourceId == null)
                            continue;

                        String workflowId = packageResourceId.getId();
                        String packageVersion = String.valueOf(packageResourceId.getVersion());
                        String packageName = readNameFromDescriptor(
                                Paths.get(targetDirPath, workflowId, packageVersion), workflowId);
                        diffs.add(buildResourceDiff(workflowId, "package", packageName));

                        // Read package file to find extension URIs
                        var dir = Paths.get(FileUtilities.buildPath(targetDirPath, workflowId, packageVersion));
                        try (var pkgStream = Files.newDirectoryStream(dir,
                                p -> p.toString().endsWith(".package.json"))) {
                            for (Path packageFilePath : pkgStream) {
                                String packageFileString = readFile(packageFilePath);
                                // Normalize legacy URIs from v5 ZIPs
                                packageFileString = normalizeLegacyUris(packageFileString);
                                addExtensionDiffs(diffs, packageFileString, dir);
                            }
                        }
                    }

                    return new ImportPreview(agentOriginId, agentName, diffs);
                }
            }

            return new ImportPreview(null, null, List.of());
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException("Preview failed: " + e.getMessage(), e);
        }
    }

    private void addExtensionDiffs(List<ResourceDiff> diffs, String packageFileString, Path packageDir)
            throws CallbackMatcher.CallbackMatcherException {

        addDiffsForType(diffs, packageFileString, DICTIONARY_URI_PATTERN, DICTIONARY_EXT, packageDir);
        addDiffsForType(diffs, packageFileString, BEHAVIOR_URI_PATTERN, BEHAVIOR_EXT, packageDir);
        addDiffsForType(diffs, packageFileString, HTTPCALLS_URI_PATTERN, HTTPCALLS_EXT, packageDir);
        addDiffsForType(diffs, packageFileString, LANGCHAIN_URI_PATTERN, LLM_EXT, packageDir);
        addDiffsForType(diffs, packageFileString, PROPERTY_URI_PATTERN, PROPERTY_EXT, packageDir);
        addDiffsForType(diffs, packageFileString, OUTPUT_URI_PATTERN, OUTPUT_EXT, packageDir);
    }

    private void addDiffsForType(List<ResourceDiff> diffs, String packageFileString,
            Pattern uriPattern, String ext, Path packageDir)
            throws CallbackMatcher.CallbackMatcherException {

        List<URI> uris = extractResourcesUris(packageFileString, uriPattern);
        for (URI uri : uris) {
            IResourceId resourceId = RestUtilities.extractResourceId(uri);
            if (resourceId == null)
                continue;
            String name = readNameFromDescriptor(packageDir, resourceId.getId());
            diffs.add(buildResourceDiff(resourceId.getId(), ext, name));
        }
    }

    private ResourceDiff buildResourceDiff(String originId, String resourceType, String name) {
        try {
            List<DocumentDescriptor> existing = documentDescriptorStore.findByOriginId(originId);
            if (!existing.isEmpty()) {
                DocumentDescriptor desc = existing.getFirst();
                IResourceId localResourceId = RestUtilities.extractResourceId(desc.getResource());
                if (localResourceId != null) {
                    return new ResourceDiff(originId, resourceType, name,
                            DiffAction.UPDATE, localResourceId.getId(), localResourceId.getVersion());
                }
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            log.debug("Could not look up origin ID " + originId + ": " + e.getMessage());
        }
        return new ResourceDiff(originId, resourceType, name, DiffAction.CREATE, null, null);
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
    public void importAgent(InputStream zippedAgentConfigFiles, String strategy,
            String selectedOriginIds, AsyncResponse response) {
        try {
            if (response != null)
                response.setTimeout(60, TimeUnit.SECONDS);
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));

            Set<String> selectedSet = parseSelectedResources(selectedOriginIds);
            boolean isMerge = STRATEGY_MERGE.equalsIgnoreCase(strategy);

            importAgentZipFile(zippedAgentConfigFiles, targetDir, response, isMerge, selectedSet);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            if (response != null) {
                response.resume(new InternalServerErrorException());
            }
        }
    }

    private Set<String> parseSelectedResources(String selectedOriginIds) {
        if (isNullOrEmpty(selectedOriginIds))
            return null;
        return Arrays.stream(selectedOriginIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private boolean isSelected(Set<String> selectedSet, String originId) {
        return selectedSet == null || selectedSet.contains(originId);
    }

    private void importAgentZipFile(InputStream zippedAgentConfigFiles, File targetDir,
            AsyncResponse response, boolean isMerge, Set<String> selectedSet)
            throws IOException {

        this.zipArchive.unzip(zippedAgentConfigFiles, targetDir);
        var targetDirPath = targetDir.getPath();
        try (var directoryStream = Files.newDirectoryStream(Paths.get(targetDirPath),
                path -> path.toString().endsWith(AGENT_FILE_ENDING))) {
            directoryStream.forEach(agentFilePath -> {
                try {
                    String agentOriginId = extractIdFromAgentFilename(agentFilePath);
                    String agentFileString = readFile(agentFilePath);

                    // Normalize legacy eddi:// URIs from v5 ZIP exports to v6 canonical form
                    agentFileString = normalizeLegacyUris(agentFileString);

                    AgentConfiguration agentConfig = jsonSerialization.deserialize(agentFileString,
                            AgentConfiguration.class);
                    agentConfig.getWorkflows()
                            .forEach(workflowUri -> parseWorkflow(targetDirPath, workflowUri, agentConfig,
                                    response, isMerge, selectedSet));

                    URI newAgentUri;
                    if (isMerge && isSelected(selectedSet, agentOriginId)) {
                        newAgentUri = createOrUpdateAgent(agentConfig, agentOriginId);
                    } else {
                        newAgentUri = createNewAgent(agentConfig);
                    }

                    updateDocumentDescriptor(Paths.get(targetDirPath), buildOldAgentUri(agentFilePath), newAgentUri);

                    // Set originId on the new agent's descriptor
                    setOriginIdOnDescriptor(newAgentUri, agentOriginId);

                    response.resume(Response.ok().location(newAgentUri).build());
                } catch (IOException | RestInterfaceFactory.RestInterfaceFactoryException e) {
                    log.error(e.getLocalizedMessage(), e);
                    response.resume(new InternalServerErrorException());
                }
            });
        }
    }

    private URI buildOldAgentUri(Path agentPath) {
        String agentPathString = agentPath.toString();
        String oldAgentId = agentPathString.substring(agentPathString.lastIndexOf(File.separator) + 1,
                agentPathString.lastIndexOf(AGENT_FILE_ENDING));

        return URI.create(IRestAgentStore.resourceURI + oldAgentId + IRestAgentStore.versionQueryParam + "1");
    }

    private void parseWorkflow(String targetDirPath, URI workflowUri, AgentConfiguration agentConfig,
            AsyncResponse response, boolean isMerge, Set<String> selectedSet) {
        try {
            IResourceId packageResourceId = RestUtilities.extractResourceId(workflowUri);
            if (packageResourceId == null) {
                return;
            }
            String workflowId = packageResourceId.getId();
            String packageVersion = String.valueOf(packageResourceId.getVersion());

            var dir = Paths.get(FileUtilities.buildPath(targetDirPath, workflowId, packageVersion));
            try (var directoryStream = Files.newDirectoryStream(dir,
                    packageFilePath -> packageFilePath.toString().endsWith(".package.json"))) {
                directoryStream.forEach(packageFilePath -> {
                    try {
                        Path packagePath = packageFilePath.getParent();
                        String packageFileString = readFile(packageFilePath);

                        // Normalize legacy eddi:// URIs from v5 ZIP exports to v6 canonical form
                        packageFileString = normalizeLegacyUris(packageFileString);

                        // loading old resources, creating/updating them,
                        // updating document descriptor and replacing references in package config

                        // ... for dictionaries
                        List<URI> dictionaryUris = extractResourcesUris(packageFileString, DICTIONARY_URI_PATTERN);
                        List<URI> newDictionaryUris = createOrUpdateResources(
                                readResources(dictionaryUris, packagePath,
                                        DICTIONARY_EXT, DictionaryConfiguration.class),
                                dictionaryUris, isMerge, selectedSet,
                                this::createNewDictionaries, this::updateDictionary);

                        updateDocumentDescriptor(packagePath, dictionaryUris, newDictionaryUris);
                        packageFileString = replaceURIs(packageFileString, dictionaryUris, newDictionaryUris);

                        // ... for behavior
                        List<URI> behaviorUris = extractResourcesUris(packageFileString, BEHAVIOR_URI_PATTERN);
                        List<URI> newBehaviorUris = createOrUpdateResources(
                                readResources(behaviorUris, packagePath,
                                        BEHAVIOR_EXT, RuleSetConfiguration.class),
                                behaviorUris, isMerge, selectedSet,
                                this::createNewBehaviors, this::updateBehavior);

                        updateDocumentDescriptor(packagePath, behaviorUris, newBehaviorUris);
                        packageFileString = replaceURIs(packageFileString, behaviorUris, newBehaviorUris);

                        // ... for http calls
                        List<URI> httpCallsUris = extractResourcesUris(packageFileString, HTTPCALLS_URI_PATTERN);
                        List<URI> newApiCallsUris = createOrUpdateResources(
                                readResources(httpCallsUris, packagePath,
                                        HTTPCALLS_EXT, ApiCallsConfiguration.class),
                                httpCallsUris, isMerge, selectedSet,
                                this::createNewApiCalls, this::updateApiCalls);

                        updateDocumentDescriptor(packagePath, httpCallsUris, newApiCallsUris);
                        packageFileString = replaceURIs(packageFileString, httpCallsUris, newApiCallsUris);

                        // ... for langchain
                        List<URI> langchainUris = extractResourcesUris(packageFileString, LANGCHAIN_URI_PATTERN);
                        List<URI> newLangchainUris = createOrUpdateResources(
                                readResources(langchainUris, packagePath,
                                        LLM_EXT, LlmConfiguration.class),
                                langchainUris, isMerge, selectedSet,
                                this::createNewLlm, this::updateLangchain);

                        updateDocumentDescriptor(packagePath, langchainUris, newLangchainUris);
                        packageFileString = replaceURIs(packageFileString, langchainUris, newLangchainUris);

                        // ... for property
                        List<URI> propertyUris = extractResourcesUris(packageFileString, PROPERTY_URI_PATTERN);
                        List<URI> newPropertyUris = createOrUpdateResources(
                                readResources(propertyUris, packagePath,
                                        PROPERTY_EXT, PropertySetterConfiguration.class),
                                propertyUris, isMerge, selectedSet,
                                this::createNewProperties, this::updateProperty);

                        updateDocumentDescriptor(packagePath, propertyUris, newPropertyUris);
                        packageFileString = replaceURIs(packageFileString, propertyUris, newPropertyUris);

                        // ... for output
                        List<URI> outputUris = extractResourcesUris(packageFileString, OUTPUT_URI_PATTERN);
                        List<URI> newOutputUris = createOrUpdateResources(
                                readResources(outputUris, packagePath,
                                        OUTPUT_EXT, OutputConfigurationSet.class),
                                outputUris, isMerge, selectedSet,
                                this::createNewOutputs, this::updateOutput);

                        updateDocumentDescriptor(packagePath, outputUris, newOutputUris);
                        packageFileString = replaceURIs(packageFileString, outputUris, newOutputUris);

                        // creating updated package and replacing references in Agent config
                        URI newWorkflowUri;
                        if (isMerge && isSelected(selectedSet, workflowId)) {
                            newWorkflowUri = createOrUpdateWorkflow(packageFileString, workflowId);
                        } else {
                            newWorkflowUri = createNewWorkflow(packageFileString);
                        }

                        // Set originId on the workflow's descriptor
                        setOriginIdOnDescriptor(newWorkflowUri, workflowId);

                        updateDocumentDescriptor(packagePath, workflowUri, newWorkflowUri);
                        agentConfig.setWorkflows(agentConfig.getWorkflows().stream()
                                .map(uri -> uri.equals(workflowUri) ? newWorkflowUri : uri)
                                .collect(Collectors.toList()));

                    } catch (IOException | RestInterfaceFactory.RestInterfaceFactoryException
                            | CallbackMatcher.CallbackMatcherException e) {
                        log.error(e.getLocalizedMessage(), e);
                        response.resume(new InternalServerErrorException());
                    }
                });

            }

        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            response.resume(new InternalServerErrorException());
        }
    }

    // ==================== Create or Update Logic ====================

    @FunctionalInterface
    private interface ResourceCreator<T> {
        List<URI> create(List<T> configs) throws RestInterfaceFactory.RestInterfaceFactoryException;
    }

    @FunctionalInterface
    private interface ResourceUpdater<T> {
        URI update(T config, String localId, Integer localVersion)
                throws RestInterfaceFactory.RestInterfaceFactoryException;
    }

    private <T> List<URI> createOrUpdateResources(
            List<T> configs, List<URI> originUris,
            boolean isMerge, Set<String> selectedSet,
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
                Response updateResponse = restAgentStore.updateAgent(
                        localResId.getId(), localResId.getVersion(), agentConfiguration);
                if (updateResponse.getStatus() == 200) {
                    // updated — new version = old version + 1
                    int newVersion = localResId.getVersion() + 1;
                    return URI.create(IRestAgentStore.resourceURI + localResId.getId() +
                            IRestAgentStore.versionQueryParam + newVersion);
                }
            }
        }
        return createNewAgent(agentConfiguration);
    }

    private URI createOrUpdateWorkflow(String packageFileString, String packageOriginId)
            throws RestInterfaceFactory.RestInterfaceFactoryException, IOException {
        URI existingUri = findLocalUriByOriginId(packageOriginId);
        if (existingUri != null) {
            IResourceId localResId = RestUtilities.extractResourceId(existingUri);
            if (localResId != null) {
                WorkflowConfiguration workflowConfig = jsonSerialization.deserialize(packageFileString,
                        WorkflowConfiguration.class);
                IRestWorkflowStore restWorkflowStore = getRestResourceStore(IRestWorkflowStore.class);
                Response updateResponse = restWorkflowStore.updateWorkflow(
                        localResId.getId(), localResId.getVersion(), workflowConfig);
                if (updateResponse.getStatus() == 200) {
                    int newVersion = localResId.getVersion() + 1;
                    return URI.create(IRestWorkflowStore.resourceURI + localResId.getId() +
                            IRestWorkflowStore.versionQueryParam + newVersion);
                }
            }
        }
        return createNewWorkflow(packageFileString);
    }

    private void setOriginIdOnDescriptor(URI resourceUri, String originId) {
        try {
            IResourceId resourceId = RestUtilities.extractResourceId(resourceUri);
            if (resourceId != null) {
                DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(resourceId.getId(),
                        resourceId.getVersion());
                if (descriptor != null && !originId.equals(descriptor.getOriginId())) {
                    descriptor.setOriginId(originId);
                    PatchInstruction<DocumentDescriptor> patchInstruction = new PatchInstruction<>();
                    patchInstruction.setOperation(PatchInstruction.PatchOperation.SET);
                    patchInstruction.setDocument(descriptor);

                    IRestDocumentDescriptorStore restDescriptorStore = getRestResourceStore(
                            IRestDocumentDescriptorStore.class);
                    restDescriptorStore.patchDescriptor(
                            resourceId.getId(), resourceId.getVersion(), patchInstruction);
                }
            }
        } catch (Exception e) {
            log.warn("Could not set originId on descriptor for " + resourceUri + ": " + e.getMessage());
        }
    }

    // ==================== Resource Creation (original logic) ====================

    private URI createNewAgent(AgentConfiguration agentConfiguration)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestAgentStore restWorkflowStore = getRestResourceStore(IRestAgentStore.class);
        Response agentResponse = restWorkflowStore.createAgent(agentConfiguration);
        checkIfCreatedResponse(agentResponse);
        return agentResponse.getLocation();
    }

    private URI createNewWorkflow(String packageFileString)
            throws RestInterfaceFactory.RestInterfaceFactoryException, IOException {
        WorkflowConfiguration workflowConfig = jsonSerialization.deserialize(packageFileString,
                WorkflowConfiguration.class);
        IRestWorkflowStore restWorkflowStore = getRestResourceStore(IRestWorkflowStore.class);
        Response packageResponse = restWorkflowStore.createWorkflow(workflowConfig);
        checkIfCreatedResponse(packageResponse);
        return packageResponse.getLocation();
    }

    private List<URI> createNewDictionaries(List<DictionaryConfiguration> dictionaryConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestDictionaryStore restDictionaryStore = getRestResourceStore(IRestDictionaryStore.class);
        return dictionaryConfigurations.stream().map(regularDictionaryConfiguration -> {
            Response dictionaryResponse = restDictionaryStore.createRegularDictionary(regularDictionaryConfiguration);
            checkIfCreatedResponse(dictionaryResponse);
            return dictionaryResponse.getLocation();
        }).toList();
    }

    private List<URI> createNewBehaviors(List<RuleSetConfiguration> behaviorConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestRuleSetStore restRuleSetStore = getRestResourceStore(IRestRuleSetStore.class);
        return behaviorConfigurations.stream().map(behaviorConfiguration -> {
            Response behaviorResponse = restRuleSetStore.createRuleSet(behaviorConfiguration);
            checkIfCreatedResponse(behaviorResponse);
            return behaviorResponse.getLocation();
        }).toList();
    }

    private List<URI> createNewApiCalls(List<ApiCallsConfiguration> httpCallsConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestApiCallsStore restApiCallsStore = getRestResourceStore(IRestApiCallsStore.class);
        return httpCallsConfigurations.stream().map(httpCallsConfiguration -> {
            Response httpCallsResponse = restApiCallsStore.createApiCalls(httpCallsConfiguration);
            checkIfCreatedResponse(httpCallsResponse);
            return httpCallsResponse.getLocation();
        }).toList();
    }

    private List<URI> createNewLlm(List<LlmConfiguration> llmConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestLlmStore restLlmStore = getRestResourceStore(IRestLlmStore.class);
        return llmConfigurations.stream().map(llmConfiguration -> {
            Response llmResponse = restLlmStore.createLlm(llmConfiguration);
            checkIfCreatedResponse(llmResponse);
            return llmResponse.getLocation();
        }).toList();
    }

    private List<URI> createNewProperties(List<PropertySetterConfiguration> propertySetterConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestPropertySetterStore restPropertySetterStore = getRestResourceStore(IRestPropertySetterStore.class);
        return propertySetterConfigurations.stream().map(propertySetterConfiguration -> {
            Response propertySetter = restPropertySetterStore.createPropertySetter(propertySetterConfiguration);
            checkIfCreatedResponse(propertySetter);
            return propertySetter.getLocation();
        }).toList();
    }

    private List<URI> createNewOutputs(List<OutputConfigurationSet> outputConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestOutputStore restOutputStore = getRestResourceStore(IRestOutputStore.class);
        return outputConfigurations.stream().map(outputConfiguration -> {
            Response outputResponse = restOutputStore.createOutputSet(outputConfiguration);
            checkIfCreatedResponse(outputResponse);
            return outputResponse.getLocation();
        }).toList();
    }

    // ==================== Resource Update (new merge logic) ====================

    private URI updateDictionary(DictionaryConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestDictionaryStore store = getRestResourceStore(IRestDictionaryStore.class);
        Response response = store.updateRegularDictionary(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestDictionaryStore.resourceURI + localId +
                    IRestDictionaryStore.versionQueryParam + (localVersion + 1));
        }
        // Fallback: create new
        Response createResp = store.createRegularDictionary(config);
        checkIfCreatedResponse(createResp);
        return createResp.getLocation();
    }

    private URI updateBehavior(RuleSetConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestRuleSetStore store = getRestResourceStore(IRestRuleSetStore.class);
        Response response = store.updateRuleSet(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestRuleSetStore.resourceURI + localId +
                    IRestRuleSetStore.versionQueryParam + (localVersion + 1));
        }
        Response createResp = store.createRuleSet(config);
        checkIfCreatedResponse(createResp);
        return createResp.getLocation();
    }

    private URI updateApiCalls(ApiCallsConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestApiCallsStore store = getRestResourceStore(IRestApiCallsStore.class);
        Response response = store.updateApiCalls(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestApiCallsStore.resourceURI + localId +
                    IRestApiCallsStore.versionQueryParam + (localVersion + 1));
        }
        Response createResp = store.createApiCalls(config);
        checkIfCreatedResponse(createResp);
        return createResp.getLocation();
    }

    private URI updateLangchain(LlmConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestLlmStore store = getRestResourceStore(IRestLlmStore.class);
        Response response = store.updateLlm(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestLlmStore.resourceURI + localId +
                    IRestLlmStore.versionQueryParam + (localVersion + 1));
        }
        Response createResp = store.createLlm(config);
        checkIfCreatedResponse(createResp);
        return createResp.getLocation();
    }

    private URI updateProperty(PropertySetterConfiguration config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestPropertySetterStore store = getRestResourceStore(IRestPropertySetterStore.class);
        Response response = store.updatePropertySetter(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestPropertySetterStore.resourceURI + localId +
                    IRestPropertySetterStore.versionQueryParam + (localVersion + 1));
        }
        Response createResp = store.createPropertySetter(config);
        checkIfCreatedResponse(createResp);
        return createResp.getLocation();
    }

    private URI updateOutput(OutputConfigurationSet config, String localId, Integer localVersion)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestOutputStore store = getRestResourceStore(IRestOutputStore.class);
        Response response = store.updateOutputSet(localId, localVersion, config);
        if (response.getStatus() == 200) {
            return URI.create(IRestOutputStore.resourceURI + localId +
                    IRestOutputStore.versionQueryParam + (localVersion + 1));
        }
        Response createResp = store.createOutputSet(config);
        checkIfCreatedResponse(createResp);
        return createResp.getLocation();
    }

    // ==================== Shared helpers ====================

    private void updateDocumentDescriptor(Path directoryPath, URI oldUri, URI newUri)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        updateDocumentDescriptor(directoryPath, Collections.singletonList(oldUri), Collections.singletonList(newUri));
    }

    private void updateDocumentDescriptor(Path directoryPath, List<URI> oldUris, List<URI> newUris)
            throws RestInterfaceFactory.RestInterfaceFactoryException {

        IRestDocumentDescriptorStore restDocumentDescriptorStore = getRestResourceStore(
                IRestDocumentDescriptorStore.class);
        IntStream.range(0, oldUris.size()).forEach(idx -> {
            try {
                URI oldUri = oldUris.get(idx);
                IResourceId oldResourceId = RestUtilities.extractResourceId(oldUri);
                if (oldResourceId != null) {
                    var oldDocumentDescriptor = readDocumentDescriptorFromFile(directoryPath, oldResourceId);

                    URI newUri = newUris.get(idx);
                    IResourceId newResourceId = RestUtilities.extractResourceId(newUri);

                    if (newResourceId != null) {
                        PatchInstruction<DocumentDescriptor> patchInstruction = new PatchInstruction<>();
                        patchInstruction.setOperation(PatchInstruction.PatchOperation.SET);
                        patchInstruction.setDocument(oldDocumentDescriptor);

                        restDocumentDescriptorStore.patchDescriptor(newResourceId.getId(),
                                newResourceId.getVersion(), patchInstruction);
                    }
                }
            } catch (IOException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        });
    }

    private DocumentDescriptor readDocumentDescriptorFromFile(Path packagePath, IResourceId resourceId)
            throws IOException {
        Path filePath = Paths
                .get(FileUtilities.buildPath(packagePath.toString(), resourceId.getId() + ".descriptor.json"));
        String oldDocumentDescriptorFile = readFile(filePath);
        return jsonSerialization.deserialize(oldDocumentDescriptorFile, DocumentDescriptor.class);
    }

    private String replaceURIs(String resourceString, List<URI> oldUris, List<URI> newUris)
            throws CallbackMatcher.CallbackMatcherException {
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
        return restInterfaceFactory.get(clazz);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> readResources(List<URI> uris, Path packagePath, String extension, Class<T> clazz) {
        return uris.stream().map(uri -> {
            Path resourcePath = null;
            String resourceContent = null;
            try {
                IResourceId resourceId = RestUtilities.extractResourceId(uri);
                if (resourceId == null) {
                    throw new IOException("resourceId was null");
                }
                resourcePath = createResourcePath(packagePath, resourceId.getId(), extension);
                resourceContent = readFile(resourcePath);
                if (uri.toString().startsWith(IRestPropertySetterStore.resourceBaseType)) {
                    var resourceAsMap = jsonSerialization.deserialize(resourceContent, Map.class);
                    var migratedPropertySetterDocument = migrationManager.migratePropertySetter()
                            .migrate(new Document(resourceAsMap));

                    if (migratedPropertySetterDocument != null) {
                        resourceContent = jsonSerialization.serialize(migratedPropertySetterDocument);
                    }
                } else if (uri.toString().startsWith(IRestApiCallsStore.resourceBaseType)) {
                    var resourceAsMap = jsonSerialization.deserialize(resourceContent, Map.class);
                    var migratedApiCallsDocument = migrationManager.migrateApiCalls()
                            .migrate(new Document(resourceAsMap));

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
                return jsonSerialization.deserialize(resourceContent, clazz);
            } catch (IOException e) {
                log.error(e.getLocalizedMessage(), e);
                log.error(String.format("uri is: %s", uri));
                log.error(String.format("packagePath is: %s", packagePath));
                log.error(String.format("resourcePath is: %s", resourcePath));
                log.error(String.format("resourceContent is:\n%s", resourceContent));
                return null;
            }
        }).collect(Collectors.toList());
    }

    private Path createResourcePath(Path packagePath, String resourceId, String extension) {
        return Paths.get(FileUtilities.buildPath(packagePath.toString(), resourceId + "." + extension + ".json"));
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
            log.error(
                    String.format("Http Response Code was not 201 when attempting resource creation, but %s", status));
        }
    }

    private static class MockAsyncResponse implements AsyncResponse {

        @Override
        public boolean resume(Object response) {
            return false;
        }

        @Override
        public boolean resume(Throwable response) {
            return false;
        }

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public boolean cancel(int retryAfter) {
            return false;
        }

        @Override
        public boolean cancel(Date retryAfter) {
            return false;
        }

        @Override
        public boolean isSuspended() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean setTimeout(long time, TimeUnit unit) {
            return false;
        }

        @Override
        public void setTimeoutHandler(TimeoutHandler handler) {

        }

        @Override
        public Collection<Class<?>> register(Class<?> callback) {
            return null;
        }

        @Override
        public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
            return null;
        }

        @Override
        public Collection<Class<?>> register(Object callback) {
            return null;
        }

        @Override
        public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
            return null;
        }
    }
}
