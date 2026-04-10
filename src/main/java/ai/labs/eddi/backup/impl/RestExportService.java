package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IRestExportService;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.backup.model.ExportPreview;
import ai.labs.eddi.backup.model.ExportPreview.ExportableResource;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.dictionary.IDictionaryStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.secrets.sanitize.SecretScrubber;
import ai.labs.eddi.utils.FileUtilities;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestExportService extends AbstractBackupService implements IRestExportService {
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IDictionaryStore regularDictionaryStore;
    private final IRuleSetStore behaviorStore;
    private final IApiCallsStore httpCallsStore;
    private final ILlmStore llmStore;
    private final IPropertySetterStore propertySetterStore;
    private final IOutputStore outputStore;
    private final IMcpCallsStore mcpCallsStore;
    private final IRagStore ragStore;
    private final IPromptSnippetStore snippetStore;
    private final IJsonSerialization jsonSerialization;
    private final IZipArchive zipArchive;
    private final SecretScrubber secretScrubber;
    private final IScheduleStore scheduleStore;
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));

    private static final Logger log = Logger.getLogger(RestExportService.class);
    private static final String SCHEDULE_EXT = "schedule";

    /**
     * Matches snippet references in template strings: {{snippets.name}} or
     * {snippets.name}. Captures the snippet name (group 1).
     */
    private static final Pattern SNIPPET_REF_PATTERN = Pattern.compile("snippets\\.([a-zA-Z0-9_\\-]+)");

    @Inject
    public RestExportService(IDocumentDescriptorStore documentDescriptorStore, IAgentStore agentStore, IWorkflowStore workflowStore,
            IDictionaryStore regularDictionaryStore, IRuleSetStore behaviorStore, IApiCallsStore httpCallsStore, ILlmStore llmStore,
            IPropertySetterStore propertySetterStore, IOutputStore outputStore, IMcpCallsStore mcpCallsStore, IRagStore ragStore,
            IPromptSnippetStore snippetStore, IJsonSerialization jsonSerialization, IZipArchive zipArchive,
            SecretScrubber secretScrubber, IScheduleStore scheduleStore) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.regularDictionaryStore = regularDictionaryStore;
        this.behaviorStore = behaviorStore;
        this.httpCallsStore = httpCallsStore;
        this.llmStore = llmStore;
        this.propertySetterStore = propertySetterStore;
        this.outputStore = outputStore;
        this.mcpCallsStore = mcpCallsStore;
        this.ragStore = ragStore;
        this.snippetStore = snippetStore;
        this.jsonSerialization = jsonSerialization;
        this.zipArchive = zipArchive;
        this.secretScrubber = secretScrubber;
        this.scheduleStore = scheduleStore;
    }

    @Override
    public Response getAgentZipArchive(String agentFilename) {
        try {
            agentFilename = sanitizeFileName(agentFilename);

            Path zipFilePath = Paths.get(tmpPath.toString(), agentFilename).normalize();

            Path tmpDirPath = Paths.get(tmpPath.toString()).toAbsolutePath().normalize();
            if (!zipFilePath.startsWith(tmpDirPath)) {
                throw new SecurityException("Invalid file path detected.");
            }

            return Response.ok(new BufferedInputStream(new FileInputStream(zipFilePath.toFile()))).build();
        } catch (FileNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response exportAgent(String agentId, Integer agentVersion, String selectedResourceIds) {
        try {
            // Validate agentId early before any path construction (CodeQL
            // java/path-injection)
            sanitizePathComponent(agentId, "agentId");

            AgentConfiguration agentConfig = agentStore.read(agentId, agentVersion);
            Path agentPath = writeDirAndDocument(agentId, agentVersion, jsonSerialization.serialize(agentConfig), tmpPath, AGENT_EXT);
            Map<IResourceId, WorkflowConfiguration> workflowConfigurations = readConfigs(workflowStore, agentConfig.getWorkflows());

            DocumentDescriptor agentDocumentDescriptor = writeDocumentDescriptor(agentPath, agentId, agentVersion);

            // Collect all serialized extension configs to scan for snippet references
            List<String> allExtensionConfigs = new ArrayList<>();

            for (IResourceId resourceId : workflowConfigurations.keySet()) {
                WorkflowConfiguration workflowConfig = workflowConfigurations.get(resourceId);
                String workflowConfigString = jsonSerialization.serialize(workflowConfig);
                Path workflowPath = writeDirAndDocument(resourceId.getId(), resourceId.getVersion(), workflowConfigString, agentPath, WORKFLOW_EXT);
                writeDocumentDescriptor(workflowPath, resourceId.getId(), resourceId.getVersion());

                Map<IResourceId, String> dictionaryConfigs = convertConfigsToString(
                        readConfigs(regularDictionaryStore, extractResourcesUris(workflowConfigString, DICTIONARY_URI_PATTERN)));
                writeConfigs(workflowPath, dictionaryConfigs, DICTIONARY_EXT);

                Map<IResourceId, String> behaviorConfigs = convertConfigsToString(
                        readConfigs(behaviorStore, extractResourcesUris(workflowConfigString, BEHAVIOR_URI_PATTERN)));
                writeConfigs(workflowPath, behaviorConfigs, BEHAVIOR_EXT);

                Map<IResourceId, String> httpCallsConfigs = convertConfigsToString(
                        readConfigs(httpCallsStore, extractResourcesUris(workflowConfigString, HTTPCALLS_URI_PATTERN)));
                writeConfigs(workflowPath, httpCallsConfigs, HTTPCALLS_EXT);

                Map<IResourceId, String> llmConfigs = convertConfigsToString(
                        readConfigs(llmStore, extractResourcesUris(workflowConfigString, LANGCHAIN_URI_PATTERN)));
                writeConfigs(workflowPath, llmConfigs, LLM_EXT);

                Map<IResourceId, String> propertyConfigs = convertConfigsToString(
                        readConfigs(propertySetterStore, extractResourcesUris(workflowConfigString, PROPERTY_URI_PATTERN)));
                writeConfigs(workflowPath, propertyConfigs, PROPERTY_EXT);

                Map<IResourceId, String> outputConfigs = convertConfigsToString(
                        readConfigs(outputStore, extractResourcesUris(workflowConfigString, OUTPUT_URI_PATTERN)));
                writeConfigs(workflowPath, outputConfigs, OUTPUT_EXT);

                Map<IResourceId, String> mcpConfigs = convertConfigsToString(
                        readConfigs(mcpCallsStore, extractResourcesUris(workflowConfigString, MCPCALLS_URI_PATTERN)));
                writeConfigs(workflowPath, mcpConfigs, MCPCALLS_EXT);

                Map<IResourceId, String> ragConfigs = convertConfigsToString(
                        readConfigs(ragStore, extractResourcesUris(workflowConfigString, RAG_URI_PATTERN)));
                writeConfigs(workflowPath, ragConfigs, RAG_EXT);

                // Collect serialized configs for snippet reference scanning
                // (snippet refs like {{snippets.name}} can appear in LLM system prompts,
                // httpCall templates, property setter instructions, and output templates)
                allExtensionConfigs.add(workflowConfigString);
                allExtensionConfigs.addAll(llmConfigs.values());
                allExtensionConfigs.addAll(httpCallsConfigs.values());
                allExtensionConfigs.addAll(propertyConfigs.values());
                allExtensionConfigs.addAll(outputConfigs.values());

                Path unusedPath = Files.createDirectories(Paths.get(tmpPath.toString(), agentId, "unused")).normalize();

                writeAllVersionsOfUris(unusedPath, regularDictionaryStore, extractResourcesUris(workflowConfigString, DICTIONARY_URI_PATTERN),
                        DICTIONARY_EXT);
                writeAllVersionsOfUris(unusedPath, behaviorStore, extractResourcesUris(workflowConfigString, BEHAVIOR_URI_PATTERN), BEHAVIOR_EXT);
                writeAllVersionsOfUris(unusedPath, httpCallsStore, extractResourcesUris(workflowConfigString, HTTPCALLS_URI_PATTERN), HTTPCALLS_EXT);
                writeAllVersionsOfUris(unusedPath, llmStore, extractResourcesUris(workflowConfigString, LANGCHAIN_URI_PATTERN), LLM_EXT);
                writeAllVersionsOfUris(unusedPath, propertySetterStore, extractResourcesUris(workflowConfigString, PROPERTY_URI_PATTERN),
                        PROPERTY_EXT);
                writeAllVersionsOfUris(unusedPath, outputStore, extractResourcesUris(workflowConfigString, OUTPUT_URI_PATTERN), OUTPUT_EXT);
                writeAllVersionsOfUris(unusedPath, mcpCallsStore, extractResourcesUris(workflowConfigString, MCPCALLS_URI_PATTERN), MCPCALLS_EXT);
                writeAllVersionsOfUris(unusedPath, ragStore, extractResourcesUris(workflowConfigString, RAG_URI_PATTERN), RAG_EXT);

            }

            // Export only snippets actually referenced by this agent's configs
            Set<String> referencedSnippetNames = extractReferencedSnippetNames(allExtensionConfigs);
            exportSnippets(agentPath, referencedSnippetNames);

            // Export schedules for this agent
            exportSchedules(agentId, agentPath);

            String zipFilename = prepareZipFilename(agentDocumentDescriptor, agentId, agentVersion);
            String targetZipPath = FileUtilities.buildPath(tmpPath.toString(), zipFilename);
            this.zipArchive.createZip(agentPath.toString(), targetZipPath, tmpPath);
            return Response.ok().location(URI.create("/backup/export/" + zipFilename)).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        } catch (IResourceStore.ResourceStoreException | IOException | CallbackMatcher.CallbackMatcherException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public ExportPreview previewExport(String agentId, Integer agentVersion) {
        try {
            sanitizePathComponent(agentId, "agentId");

            AgentConfiguration agentConfig = agentStore.read(agentId, agentVersion);
            DocumentDescriptor agentDescriptor = documentDescriptorStore.readDescriptor(agentId, agentVersion);
            String agentName = agentDescriptor != null ? agentDescriptor.getName() : null;

            List<ExportableResource> resources = new ArrayList<>();

            // Agent itself (always required)
            resources.add(new ExportableResource(agentId, agentVersion, "agent", agentName, null, -1, true));

            // Walk workflows
            Map<IResourceId, WorkflowConfiguration> workflowConfigs = readConfigs(workflowStore, agentConfig.getWorkflows());
            int wfIndex = 0;
            for (Map.Entry<IResourceId, WorkflowConfiguration> wfEntry : workflowConfigs.entrySet()) {
                IResourceId wfResId = wfEntry.getKey();
                WorkflowConfiguration wfConfig = wfEntry.getValue();
                String wfId = wfResId.getId();
                DocumentDescriptor wfDescriptor = documentDescriptorStore.readDescriptor(wfId, wfResId.getVersion());
                String wfName = wfDescriptor != null ? wfDescriptor.getName() : null;

                // Workflow skeleton (always required)
                resources.add(new ExportableResource(wfId, wfResId.getVersion(), "workflow", wfName, null, wfIndex, true));

                // Walk extensions within this workflow
                String wfJson = jsonSerialization.serialize(wfConfig);
                addExtensionResources(resources, wfJson, wfId, wfIndex);

                wfIndex++;
            }

            // Snippets
            List<String> allExtensionConfigs = new ArrayList<>();
            for (WorkflowConfiguration wfConfig : workflowConfigs.values()) {
                String wfJson = jsonSerialization.serialize(wfConfig);
                allExtensionConfigs.add(wfJson);
                // Also scan extension content for snippet refs
                addExtensionContentForSnippetScan(allExtensionConfigs, wfJson);
            }
            Set<String> referencedSnippetNames = extractReferencedSnippetNames(allExtensionConfigs);
            for (String snippetName : referencedSnippetNames) {
                resources.add(new ExportableResource(snippetName, 0, "snippet", snippetName, null, -1, false));
            }

            return new ExportPreview(agentId, agentName, agentVersion, resources);

        } catch (Exception e) {
            log.error("Export preview failed: " + e.getMessage(), e);
            throw sneakyThrow(e);
        }
    }

    private void addExtensionResources(List<ExportableResource> resources, String wfJson,
                                       String parentWorkflowId, int workflowIndex) {
        addExtensionResourcesForType(resources, wfJson, DICTIONARY_URI_PATTERN, "regulardictionary", parentWorkflowId);
        addExtensionResourcesForType(resources, wfJson, BEHAVIOR_URI_PATTERN, "behavior", parentWorkflowId);
        addExtensionResourcesForType(resources, wfJson, HTTPCALLS_URI_PATTERN, "httpcalls", parentWorkflowId);
        addExtensionResourcesForType(resources, wfJson, LANGCHAIN_URI_PATTERN, "langchain", parentWorkflowId);
        addExtensionResourcesForType(resources, wfJson, PROPERTY_URI_PATTERN, "property", parentWorkflowId);
        addExtensionResourcesForType(resources, wfJson, OUTPUT_URI_PATTERN, "output", parentWorkflowId);
        addExtensionResourcesForType(resources, wfJson, MCPCALLS_URI_PATTERN, "mcpcalls", parentWorkflowId);
        addExtensionResourcesForType(resources, wfJson, RAG_URI_PATTERN, "rag", parentWorkflowId);
    }

    private void addExtensionResourcesForType(List<ExportableResource> resources, String wfJson,
                                              Pattern uriPattern, String typeLabel, String parentWorkflowId) {
        try {
            List<URI> uris = extractResourcesUris(wfJson, uriPattern);
            for (URI uri : uris) {
                IResourceId resId = RestUtilities.extractResourceId(uri);
                if (resId == null)
                    continue;
                DocumentDescriptor desc = null;
                try {
                    desc = documentDescriptorStore.readDescriptor(resId.getId(), resId.getVersion());
                } catch (Exception ignored) {
                }
                String name = desc != null ? desc.getName() : null;
                resources.add(new ExportableResource(resId.getId(), resId.getVersion(), typeLabel, name, parentWorkflowId, -1, false));
            }
        } catch (Exception e) {
            log.debugf("Could not extract %s resources from workflow: %s", typeLabel, e.getMessage());
        }
    }

    private void addExtensionContentForSnippetScan(List<String> allConfigs, String wfJson) {
        try {
            for (IResourceId resId : readConfigs(llmStore, extractResourcesUris(wfJson, LANGCHAIN_URI_PATTERN)).keySet()) {
                allConfigs.add(jsonSerialization.serialize(llmStore.read(resId.getId(), resId.getVersion())));
            }
            for (IResourceId resId : readConfigs(httpCallsStore, extractResourcesUris(wfJson, HTTPCALLS_URI_PATTERN)).keySet()) {
                allConfigs.add(jsonSerialization.serialize(httpCallsStore.read(resId.getId(), resId.getVersion())));
            }
            for (IResourceId resId : readConfigs(propertySetterStore, extractResourcesUris(wfJson, PROPERTY_URI_PATTERN)).keySet()) {
                allConfigs.add(jsonSerialization.serialize(propertySetterStore.read(resId.getId(), resId.getVersion())));
            }
            for (IResourceId resId : readConfigs(outputStore, extractResourcesUris(wfJson, OUTPUT_URI_PATTERN)).keySet()) {
                allConfigs.add(jsonSerialization.serialize(outputStore.read(resId.getId(), resId.getVersion())));
            }
        } catch (Exception e) {
            log.debugf("Could not scan extension content for snippets: %s", e.getMessage());
        }
    }

    private <T> void writeAllVersionsOfUris(Path unusedPath, IResourceStore<T> store, List<URI> dictionaryUris, String ext) {
        for (URI dictionaryUri : dictionaryUris) {
            Integer versionToExport = 1;
            IResourceId resourceIdUnused = RestUtilities.extractResourceId(dictionaryUri);
            final String strResId = resourceIdUnused.getId();
            Map<IResourceId, T> toStore = new LinkedHashMap<>();
            T config = null;
            try {
                config = store.readIncludingDeleted(resourceIdUnused.getId(), versionToExport);
            } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
                log.error(e.getLocalizedMessage(), e);
            }
            while (versionToExport < 10000) {
                try {
                    toStore.put(resourceIdUnused, config);
                    final Integer currentVersion = versionToExport;
                    resourceIdUnused = new IResourceId() {

                        @Override
                        public String getId() {
                            return strResId;
                        }

                        @Override
                        public Integer getVersion() {
                            return currentVersion;
                        }
                    };
                    config = store.readIncludingDeleted(resourceIdUnused.getId(), versionToExport);
                } catch (IResourceStore.ResourceNotFoundException | IRuleSetStore.ResourceStoreException ex) {
                    break;
                }
                versionToExport++;
            }
            writeUnusedConfigs(unusedPath, convertConfigsToString(toStore), ext);
        }

    }

    private String prepareZipFilename(DocumentDescriptor agentDocumentDescriptor, String agentId, Integer agentVersion)
            throws UnsupportedEncodingException {
        String zipFilename = "";
        if (!isNullOrEmpty(agentDocumentDescriptor.getName())) {
            zipFilename = URLEncoder.encode(agentDocumentDescriptor.getName() + "-", StandardCharsets.UTF_8);
        }
        zipFilename += agentId + "-" + agentVersion + ".zip";
        return zipFilename;
    }

    private Map<IResourceId, String> convertConfigsToString(Map<IResourceId, ?> configurationMap) {
        return configurationMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> {
            try {
                String json = jsonSerialization.serialize(e.getValue());
                // Scrub secrets before export (defense-in-depth)
                return secretScrubber.scrubJson(json);
            } catch (IOException ex) {
                log.error(ex.getLocalizedMessage(), ex);
                return "";
            }
        }));
    }

    private void writeConfigs(Path path, Map<IResourceId, String> configs, String fileExtension) {
        configs.forEach((resourceId, value) -> {
            String filename = MessageFormat.format("{0}.{1}.json", resourceId.getId(), fileExtension);
            Path filePath = Paths.get(path.toString(), filename);
            try {
                deleteFileIfExists(filePath);
                try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                    writer.write(value);
                    writeDocumentDescriptor(path, resourceId.getId(), resourceId.getVersion());
                }
            } catch (IOException | IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        });
    }

    private void writeUnusedConfigs(Path path, Map<IResourceId, String> configs, String fileExtension) {
        configs.forEach((resourceId, value) -> {
            String filename = MessageFormat.format("{0}.{1}.{2}.json", resourceId.getId(), resourceId.getVersion(), fileExtension);
            Path filePath = Paths.get(path.toString(), filename);
            try {
                deleteFileIfExists(filePath);
                try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                    writer.write(value);
                    writeDocumentDescriptor(path, resourceId.getId(), resourceId.getVersion());
                }
            } catch (IOException | IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        });
    }

    private Path writeDirAndDocument(String documentId, Integer documentVersion, String configurationString, Path tmpPath, String fileExtension)
            throws IOException {
        // Validate path components to prevent path injection (CodeQL
        // java/path-injection)
        sanitizePathComponent(documentId, "documentId");

        Path dir = Files.createDirectories(Paths.get(tmpPath.toString(), documentId, String.valueOf(documentVersion))).normalize();
        if (!dir.toAbsolutePath().startsWith(tmpPath.toAbsolutePath())) {
            throw new IOException("Path traversal detected in documentId");
        }

        String filename = MessageFormat.format("{0}.{1}.json", documentId, fileExtension);
        Path filePath = Paths.get(dir.toString(), filename);
        deleteFileIfExists(filePath);
        Path agentConfigFilePath = Files.createFile(filePath);
        try (BufferedWriter writer = Files.newBufferedWriter(agentConfigFilePath)) {
            writer.write(configurationString);
        }

        return dir;
    }

    private DocumentDescriptor writeDocumentDescriptor(Path path, String documentId, Integer documentVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IOException {
        DocumentDescriptor documentDescriptor = documentDescriptorStore.readDescriptorWithHistory(documentId, documentVersion);
        String filename = MessageFormat.format("{0}.descriptor.json", documentId);
        Path filePath = Paths.get(path.toString(), filename);
        deleteFileIfExists(filePath);
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            writer.write(jsonSerialization.serialize(documentDescriptor));
        }

        return documentDescriptor;
    }

    private static <T> Map<IResourceId, T> readConfigs(IResourceStore<T> store, List<URI> configUris)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        Map<IResourceId, T> ret = new LinkedHashMap<>();
        for (URI uri : configUris) {
            IResourceId resourceId = RestUtilities.extractResourceId(uri);
            ret.put(resourceId, store.read(resourceId.getId(), resourceId.getVersion()));
        }

        return ret;
    }

    private void deleteFileIfExists(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.delete(path);
        }
    }

    private static String sanitizeFileName(String agentFilename) {
        if (agentFilename == null || agentFilename.isEmpty()) {
            throw new BadRequestException("Filename is empty.");
        }

        if (!agentFilename.matches("^[a-zA-Z0-9_.+\\-]+$")) {
            throw new BadRequestException("Filename contains invalid characters.");
        }

        return agentFilename;
    }

    /**
     * Validates that a string used as a path component does not contain directory
     * traversal sequences or path separators (CodeQL java/path-injection).
     */
    private static void sanitizePathComponent(String value, String paramName) {
        if (value == null || value.isEmpty()) {
            throw new BadRequestException(paramName + " must not be empty");
        }
        if (value.contains("..") || value.contains("/") || value.contains("\\")) {
            throw new BadRequestException(paramName + " contains invalid path characters");
        }
    }

    /**
     * Scans serialized config strings for {@code snippets.<name>} template
     * references.
     *
     * @return set of snippet names referenced by any config
     */
    private Set<String> extractReferencedSnippetNames(List<String> configStrings) {
        Set<String> names = new LinkedHashSet<>();
        for (String config : configStrings) {
            if (config == null || config.isEmpty())
                continue;
            Matcher matcher = SNIPPET_REF_PATTERN.matcher(config);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    /**
     * Exports only snippets whose {@code name} is in the referenced set. If the
     * referenced set is empty, no snippets are exported.
     */
    private void exportSnippets(Path agentPath, Set<String> referencedNames) {
        if (referencedNames.isEmpty()) {
            return;
        }

        try {
            List<DocumentDescriptor> descriptors = documentDescriptorStore.readDescriptors(
                    "ai.labs.snippet", "", 0, 0, false);
            if (descriptors == null || descriptors.isEmpty()) {
                return;
            }

            Path snippetsDir = Files.createDirectories(Paths.get(agentPath.toString(), "snippets"));
            int exportedCount = 0;
            for (DocumentDescriptor descriptor : descriptors) {
                try {
                    java.net.URI resourceUri = descriptor.getResource();
                    IResourceId resourceId = RestUtilities.extractResourceId(resourceUri);
                    if (resourceId == null)
                        continue;

                    PromptSnippet snippet = snippetStore.read(resourceId.getId(), resourceId.getVersion());
                    if (snippet == null || snippet.getName() == null)
                        continue;

                    // Only export snippets actually referenced by this agent
                    if (!referencedNames.contains(snippet.getName())) {
                        continue;
                    }

                    String json = jsonSerialization.serialize(snippet);
                    json = secretScrubber.scrubJson(json);
                    String filename = resourceId.getId() + "." + SNIPPET_EXT + ".json";
                    Path filePath = Paths.get(snippetsDir.toString(), filename);
                    deleteFileIfExists(filePath);
                    try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                        writer.write(json);
                    }

                    // Write descriptor
                    String descriptorFilename = resourceId.getId() + ".descriptor.json";
                    Path descriptorPath = Paths.get(snippetsDir.toString(), descriptorFilename);
                    deleteFileIfExists(descriptorPath);
                    try (BufferedWriter writer = Files.newBufferedWriter(descriptorPath)) {
                        writer.write(jsonSerialization.serialize(descriptor));
                    }
                    exportedCount++;
                } catch (IResourceStore.ResourceNotFoundException e) {
                    log.debugf("Snippet descriptor references missing resource: %s", descriptor.getResource());
                }
            }
            if (exportedCount > 0) {
                log.infof("Exported %d snippet(s) (referenced: %s)", exportedCount, referencedNames);
            }
        } catch (Exception e) {
            log.warnf("Failed to export snippets: %s", e.getMessage());
        }
    }

    private void exportSchedules(String agentId, Path agentPath) {
        try {
            List<ScheduleConfiguration> schedules = scheduleStore.readSchedulesByAgentId(agentId);
            if (schedules.isEmpty()) {
                return;
            }

            Path schedulesDir = Files.createDirectories(Paths.get(agentPath.toString(), "schedules"));
            for (ScheduleConfiguration schedule : schedules) {
                String json = jsonSerialization.serialize(schedule);
                json = secretScrubber.scrubJson(json);
                String filename = schedule.getId() + "." + SCHEDULE_EXT + ".json";
                Path filePath = Paths.get(schedulesDir.toString(), filename);
                deleteFileIfExists(filePath);
                try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
                    writer.write(json);
                }
            }
            log.infof("Exported %d schedule(s) for Agent %s", schedules.size(), agentId);
        } catch (Exception e) {
            log.warnf("Failed to export schedules for Agent %s: %s", agentId, e.getMessage());
        }
    }
}
