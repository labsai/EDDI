package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IRestExportService;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.behavior.IBehaviorStore;
import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.git.IGitCallsStore;
import ai.labs.eddi.configs.http.IHttpCallsStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.propertysetter.IPropertySetterStore;
import ai.labs.eddi.configs.regulardictionary.IRegularDictionaryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.models.DocumentDescriptor;
import ai.labs.eddi.utils.FileUtilities;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
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
    private final IBotStore botStore;
    private final IPackageStore packageStore;
    private final IRegularDictionaryStore regularDictionaryStore;
    private final IBehaviorStore behaviorStore;
    private final IHttpCallsStore httpCallsStore;
    private final IPropertySetterStore propertySetterStore;
    private final IOutputStore outputStore;
    private final IJsonSerialization jsonSerialization;
    private final IZipArchive zipArchive;
    private final IGitCallsStore gitCallsStore;
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));

    private static final Logger log = Logger.getLogger(RestExportService.class);

    @Inject
    public RestExportService(IDocumentDescriptorStore documentDescriptorStore,
                             IBotStore botStore,
                             IPackageStore packageStore,
                             IRegularDictionaryStore regularDictionaryStore,
                             IBehaviorStore behaviorStore,
                             IHttpCallsStore httpCallsStore,
                             IPropertySetterStore propertySetterStore,
                             IOutputStore outputStore,
                             IJsonSerialization jsonSerialization,
                             IZipArchive zipArchive,
                             IGitCallsStore gitCallsStore) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.botStore = botStore;
        this.packageStore = packageStore;
        this.regularDictionaryStore = regularDictionaryStore;
        this.behaviorStore = behaviorStore;
        this.httpCallsStore = httpCallsStore;
        this.propertySetterStore = propertySetterStore;
        this.outputStore = outputStore;
        this.jsonSerialization = jsonSerialization;
        this.zipArchive = zipArchive;
        this.gitCallsStore = gitCallsStore;
    }

    @Override
    public Response getBotZipArchive(String botFilename) {
        try {
            String zipFilePath = FileUtilities.buildPath(tmpPath.toString(), botFilename);
            return Response.ok(new BufferedInputStream(new FileInputStream(zipFilePath))).build();
        } catch (FileNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public Response exportBot(String botId, Integer botVersion) {
        try {
            BotConfiguration botConfiguration = botStore.read(botId, botVersion);
            Path botPath = writeDirAndDocument(botId, botVersion, jsonSerialization.serialize(botConfiguration), tmpPath, BOT_EXT);
            Map<IResourceId, PackageConfiguration> packageConfigurations =
                    readConfigs(packageStore, botConfiguration.getPackages());

            DocumentDescriptor botDocumentDescriptor = writeDocumentDescriptor(botPath, botId, botVersion);

            for (IResourceId resourceId : packageConfigurations.keySet()) {
                PackageConfiguration packageConfiguration = packageConfigurations.get(resourceId);
                String packageConfigurationString = jsonSerialization.serialize(packageConfiguration);
                Path packagePath = writeDirAndDocument(resourceId.getId(), resourceId.getVersion(),
                        packageConfigurationString, botPath, PACKAGE_EXT);
                writeDocumentDescriptor(packagePath, resourceId.getId(), resourceId.getVersion());

                writeConfigs(packagePath, convertConfigsToString(readConfigs(regularDictionaryStore,
                        extractResourcesUris(packageConfigurationString, DICTIONARY_URI_PATTERN))), DICTIONARY_EXT);

                writeConfigs(packagePath, convertConfigsToString(readConfigs(behaviorStore,
                        extractResourcesUris(packageConfigurationString, BEHAVIOR_URI_PATTERN))), BEHAVIOR_EXT);

                writeConfigs(packagePath, convertConfigsToString(readConfigs(httpCallsStore,
                        extractResourcesUris(packageConfigurationString, HTTPCALLS_URI_PATTERN))), HTTPCALLS_EXT);

                writeConfigs(packagePath, convertConfigsToString(readConfigs(propertySetterStore,
                        extractResourcesUris(packageConfigurationString, PROPERTY_URI_PATTERN))), PROPERTY_EXT);

                writeConfigs(packagePath, convertConfigsToString(readConfigs(outputStore,
                        extractResourcesUris(packageConfigurationString, OUTPUT_URI_PATTERN))), OUTPUT_EXT);

                writeConfigs(packagePath, convertConfigsToString(readConfigs(gitCallsStore,
                        extractResourcesUris(packageConfigurationString, GITCALLS_URI_PATTERN))), GITCALLS_EXT);

                Path unusedPath = Files.createDirectories(Paths.get(tmpPath.toString(), botId, "unused"));

                writeAllVersionsOfUris(unusedPath, regularDictionaryStore, extractResourcesUris(packageConfigurationString, DICTIONARY_URI_PATTERN), DICTIONARY_EXT);
                writeAllVersionsOfUris(unusedPath, behaviorStore, extractResourcesUris(packageConfigurationString, BEHAVIOR_URI_PATTERN), BEHAVIOR_EXT);
                writeAllVersionsOfUris(unusedPath, httpCallsStore, extractResourcesUris(packageConfigurationString, HTTPCALLS_URI_PATTERN), HTTPCALLS_EXT);
                writeAllVersionsOfUris(unusedPath, propertySetterStore, extractResourcesUris(packageConfigurationString, PROPERTY_URI_PATTERN), PROPERTY_EXT);
                writeAllVersionsOfUris(unusedPath, outputStore, extractResourcesUris(packageConfigurationString, OUTPUT_URI_PATTERN), OUTPUT_EXT);
                writeAllVersionsOfUris(unusedPath, gitCallsStore, extractResourcesUris(packageConfigurationString, GITCALLS_URI_PATTERN), GITCALLS_EXT);

            }

            String zipFilename = prepareZipFilename(botDocumentDescriptor, botId, botVersion);
            String targetZipPath = FileUtilities.buildPath(tmpPath.toString(), zipFilename);
            this.zipArchive.createZip(botPath.toString(), targetZipPath);
            return Response.ok().location(URI.create("/backup/export/" + zipFilename)).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException | IOException | CallbackMatcher.CallbackMatcherException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
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
                } catch (IResourceStore.ResourceNotFoundException | IBehaviorStore.ResourceStoreException ex) {
                    break;
                }
                versionToExport++;
            }
            writeUnusedConfigs(unusedPath, convertConfigsToString(toStore), ext);
        }

    }

    private String prepareZipFilename(DocumentDescriptor botDocumentDescriptor, String botId, Integer botVersion)
            throws UnsupportedEncodingException {
        String zipFilename = "";
        if (!isNullOrEmpty(botDocumentDescriptor.getName())) {
            zipFilename = URLEncoder.encode(botDocumentDescriptor.getName() + "-", StandardCharsets.UTF_8.toString());
        }
        zipFilename += botId + "-" + botVersion + ".zip";
        return zipFilename;
    }

    private Map<IResourceId, String> convertConfigsToString(Map<IResourceId, ?> configurationMap) {
        return configurationMap.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> {
                    try {
                        return jsonSerialization.serialize(e.getValue());
                    } catch (IOException ex) {
                        log.error(ex.getLocalizedMessage(), ex);
                        return "";
                    }
                }
        ));
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


    private Path writeDirAndDocument(String documentId, Integer documentVersion,
                                     String configurationString, Path tmpPath, String fileExtension)
            throws IOException {

        Path dir = Files.createDirectories(Paths.get(tmpPath.toString(), documentId, String.valueOf(documentVersion)));

        String filename = MessageFormat.format("{0}.{1}.json", documentId, fileExtension);
        Path filePath = Paths.get(dir.toString(), filename);
        deleteFileIfExists(filePath);
        Path botConfigFilePath = Files.createFile(filePath);
        try (BufferedWriter writer = Files.newBufferedWriter(botConfigFilePath)) {
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
}
