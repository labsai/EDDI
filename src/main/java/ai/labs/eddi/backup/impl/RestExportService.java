package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IRestExportService;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.output.IOutputStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final IJsonSerialization jsonSerialization;
    private final IZipArchive zipArchive;
    private final SecretScrubber secretScrubber;
    private final IScheduleStore scheduleStore;
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));

    private static final Logger log = Logger.getLogger(RestExportService.class);
    private static final String SCHEDULE_EXT = "schedule";

    @Inject
    public RestExportService(IDocumentDescriptorStore documentDescriptorStore, IAgentStore agentStore, IWorkflowStore workflowStore,
            IDictionaryStore regularDictionaryStore, IRuleSetStore behaviorStore, IApiCallsStore httpCallsStore, ILlmStore llmStore,
            IPropertySetterStore propertySetterStore, IOutputStore outputStore, IJsonSerialization jsonSerialization, IZipArchive zipArchive,
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
    public Response exportAgent(String agentId, Integer agentVersion) {
        try {
            // Validate agentId early before any path construction (CodeQL
            // java/path-injection)
            sanitizePathComponent(agentId, "agentId");

            AgentConfiguration agentConfig = agentStore.read(agentId, agentVersion);
            Path agentPath = writeDirAndDocument(agentId, agentVersion, jsonSerialization.serialize(agentConfig), tmpPath, AGENT_EXT);
            Map<IResourceId, WorkflowConfiguration> workflowConfigurations = readConfigs(workflowStore, agentConfig.getWorkflows());

            DocumentDescriptor agentDocumentDescriptor = writeDocumentDescriptor(agentPath, agentId, agentVersion);

            for (IResourceId resourceId : workflowConfigurations.keySet()) {
                WorkflowConfiguration workflowConfig = workflowConfigurations.get(resourceId);
                String workflowConfigString = jsonSerialization.serialize(workflowConfig);
                Path workflowPath = writeDirAndDocument(resourceId.getId(), resourceId.getVersion(), workflowConfigString, agentPath, WORKFLOW_EXT);
                writeDocumentDescriptor(workflowPath, resourceId.getId(), resourceId.getVersion());

                writeConfigs(workflowPath,
                        convertConfigsToString(
                                readConfigs(regularDictionaryStore, extractResourcesUris(workflowConfigString, DICTIONARY_URI_PATTERN))),
                        DICTIONARY_EXT);

                writeConfigs(workflowPath,
                        convertConfigsToString(readConfigs(behaviorStore, extractResourcesUris(workflowConfigString, BEHAVIOR_URI_PATTERN))),
                        BEHAVIOR_EXT);

                writeConfigs(workflowPath,
                        convertConfigsToString(readConfigs(httpCallsStore, extractResourcesUris(workflowConfigString, HTTPCALLS_URI_PATTERN))),
                        HTTPCALLS_EXT);

                writeConfigs(workflowPath,
                        convertConfigsToString(readConfigs(llmStore, extractResourcesUris(workflowConfigString, LANGCHAIN_URI_PATTERN))), LLM_EXT);

                writeConfigs(workflowPath,
                        convertConfigsToString(readConfigs(propertySetterStore, extractResourcesUris(workflowConfigString, PROPERTY_URI_PATTERN))),
                        PROPERTY_EXT);

                writeConfigs(workflowPath,
                        convertConfigsToString(readConfigs(outputStore, extractResourcesUris(workflowConfigString, OUTPUT_URI_PATTERN))), OUTPUT_EXT);

                Path unusedPath = Files.createDirectories(Paths.get(tmpPath.toString(), agentId, "unused")).normalize();

                writeAllVersionsOfUris(unusedPath, regularDictionaryStore, extractResourcesUris(workflowConfigString, DICTIONARY_URI_PATTERN),
                        DICTIONARY_EXT);
                writeAllVersionsOfUris(unusedPath, behaviorStore, extractResourcesUris(workflowConfigString, BEHAVIOR_URI_PATTERN), BEHAVIOR_EXT);
                writeAllVersionsOfUris(unusedPath, httpCallsStore, extractResourcesUris(workflowConfigString, HTTPCALLS_URI_PATTERN), HTTPCALLS_EXT);
                writeAllVersionsOfUris(unusedPath, llmStore, extractResourcesUris(workflowConfigString, LANGCHAIN_URI_PATTERN), LLM_EXT);
                writeAllVersionsOfUris(unusedPath, propertySetterStore, extractResourcesUris(workflowConfigString, PROPERTY_URI_PATTERN),
                        PROPERTY_EXT);
                writeAllVersionsOfUris(unusedPath, outputStore, extractResourcesUris(workflowConfigString, OUTPUT_URI_PATTERN), OUTPUT_EXT);

            }

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
