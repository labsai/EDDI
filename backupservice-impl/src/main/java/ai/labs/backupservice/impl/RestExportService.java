package ai.labs.backupservice.impl;

import ai.labs.backupservice.IRestExportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.IResourceStore.IResourceId;
import ai.labs.resources.rest.behavior.IBehaviorStore;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.output.IOutputStore;
import ai.labs.resources.rest.packages.IPackageStore;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.resources.rest.regulardictionary.IRegularDictionaryStore;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.FileUtilities;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author ginccc
 */
@Slf4j
public class RestExportService implements IRestExportService {
    private static final String CONFIG_KEY_URI = "uri";
    private static final String PARSER_URI = "eddi://ai.labs.parser";
    private static final String DICTIONARY_URI = "eddi://ai.labs.parser.dictionaries.regular";
    private static final String BEHAVIOR_URI = "eddi://ai.labs.behavior";
    private static final String OUTPUT_URI = "eddi://ai.labs.output";
    private static final String BOT_EXT = "bot";
    private static final String PACKAGE_EXT = "package";
    private static final String DICTIONARY_EXT = "regulardictionary";
    private static final String BEHAVIOR_EXT = "behavior";
    private static final String OUTPUT_EXT = "output";
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IBotStore botStore;
    private final IPackageStore packageStore;
    private final IRegularDictionaryStore regularDictionaryStore;
    private final IBehaviorStore behaviorStore;
    private final IOutputStore outputStore;
    private final IJsonSerialization jsonSerialization;
    private final IZipArchive zipArchive;
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));

    @Inject
    public RestExportService(IDocumentDescriptorStore documentDescriptorStore,
                             IBotStore botStore,
                             IPackageStore packageStore,
                             IRegularDictionaryStore regularDictionaryStore,
                             IBehaviorStore behaviorStore,
                             IOutputStore outputStore,
                             IJsonSerialization jsonSerialization,
                             IZipArchive zipArchive) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.botStore = botStore;
        this.packageStore = packageStore;
        this.regularDictionaryStore = regularDictionaryStore;
        this.behaviorStore = behaviorStore;
        this.outputStore = outputStore;
        this.jsonSerialization = jsonSerialization;
        this.zipArchive = zipArchive;
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

            for (IResourceId resourceId : packageConfigurations.keySet()) {
                PackageConfiguration packageConfiguration = packageConfigurations.get(resourceId);
                String packageConfigurationString = jsonSerialization.serialize(packageConfiguration);
                Path packagePath = writeDirAndDocument(resourceId.getId(), resourceId.getVersion(),
                        packageConfigurationString, botPath, PACKAGE_EXT);

                writeConfigs(packagePath, convertConfigsToString(readConfigs(regularDictionaryStore,
                        extractRegularDictionaries(packageConfiguration))), DICTIONARY_EXT);

                writeConfigs(packagePath, convertConfigsToString(readConfigs(behaviorStore,
                        extractResources(packageConfiguration, BEHAVIOR_URI))), BEHAVIOR_EXT);

                writeConfigs(packagePath, convertConfigsToString(readConfigs(outputStore,
                        extractResources(packageConfiguration, OUTPUT_URI))), OUTPUT_EXT);
            }

            String zipFilename = botId + "-" + botVersion + ".zip";
            String targetZipPath = FileUtilities.buildPath(tmpPath.toString(), zipFilename);
            this.zipArchive.createZip(botPath.toString(), targetZipPath);
            return Response.created(URI.create("/backup/export/" + zipFilename)).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceStoreException | IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
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

    private List<URI> extractRegularDictionaries(PackageConfiguration packageConfiguration) {
        return packageConfiguration.getPackageExtensions().stream().
                filter(packageExtension ->
                        packageExtension.getType().toString().startsWith(PARSER_URI) &&
                                packageExtension.getExtensions().containsKey("dictionaries")).
                flatMap(packageExtension -> {
                    Map<String, Object> extensions = packageExtension.getExtensions();
                    for (String extensionKey : extensions.keySet()) {
                        List<Map<String, Object>> extensionElements = (List<Map<String, Object>>) extensions.get(extensionKey);
                        for (Map<String, Object> extensionElement : extensionElements) {
                            if (DICTIONARY_URI.equals(extensionElement.get("type")) &&
                                    extensionElement.containsKey("config")) {
                                Map<String, String> config = (Map<String, String>) extensionElement.get("config");
                                if (config.containsKey(CONFIG_KEY_URI)) {
                                    return Stream.of(URI.create(config.get(CONFIG_KEY_URI)));
                                }
                            }
                        }
                    }

                    return Stream.empty();
                }).collect(Collectors.toList());
    }

    private List<URI> extractResources(PackageConfiguration packageConfiguration, String type) {
        return packageConfiguration.getPackageExtensions().stream().
                filter(packageExtension ->
                        packageExtension.getType().toString().startsWith(type) &&
                                packageExtension.getConfig().containsKey(CONFIG_KEY_URI)).
                map(packageExtension ->
                        URI.create(packageExtension.getConfig().get(CONFIG_KEY_URI).toString())).
                collect(Collectors.toList());
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

    private Path writeDirAndDocument(String documentId, Integer documentVersion,
                                     String configurationString, Path tmpPath, String fileExtension)
            throws IOException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

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

    private void writeDocumentDescriptor(Path path, String documentId, Integer documentVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IOException {
        DocumentDescriptor documentDescriptor = documentDescriptorStore.readDescriptor(documentId, documentVersion);
        String filename = MessageFormat.format("{0}.descriptor.json", documentId);
        Path filePath = Paths.get(path.toString(), filename);
        deleteFileIfExists(filePath);
        try (BufferedWriter writer = Files.newBufferedWriter(filePath)) {
            writer.write(jsonSerialization.serialize(documentDescriptor));
        }
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