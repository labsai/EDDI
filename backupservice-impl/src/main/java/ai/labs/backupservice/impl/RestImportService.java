package ai.labs.backupservice.impl;

import ai.labs.backupservice.IRestImportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.models.BotDeploymentStatus;
import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.config.behavior.IRestBehaviorStore;
import ai.labs.resources.rest.config.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.config.bots.IRestBotStore;
import ai.labs.resources.rest.config.bots.model.BotConfiguration;
import ai.labs.resources.rest.config.http.IRestHttpCallsStore;
import ai.labs.resources.rest.config.http.model.HttpCallsConfiguration;
import ai.labs.resources.rest.config.output.IRestOutputStore;
import ai.labs.resources.rest.config.output.model.OutputConfigurationSet;
import ai.labs.resources.rest.config.packages.IRestPackageStore;
import ai.labs.resources.rest.config.packages.model.PackageConfiguration;
import ai.labs.resources.rest.config.propertysetter.IRestPropertySetterStore;
import ai.labs.resources.rest.config.propertysetter.model.PropertySetterConfiguration;
import ai.labs.resources.rest.config.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.resources.rest.config.regulardictionary.model.RegularDictionaryConfiguration;
import ai.labs.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.resources.rest.patch.PatchInstruction;
import ai.labs.rest.restinterfaces.IRestBotAdministration;
import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.FileUtilities;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.TimeoutHandler;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static ai.labs.models.Deployment.Environment.unrestricted;
import static ai.labs.persistence.IResourceStore.IResourceId;

/**
 * @author ginccc
 */
@Slf4j
public class RestImportService extends AbstractBackupService implements IRestImportService {
    private static final Pattern EDDI_URI_PATTERN = Pattern.compile("\"eddi://ai.labs..*?\"");
    private static final String BOT_FILE_ENDING = ".bot.json";
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp", "import"));
    private final Path examplePath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "resources", "examples"));
    private final IZipArchive zipArchive;
    private final IJsonSerialization jsonSerialization;
    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestBotAdministration restBotAdministration;

    @Inject
    public RestImportService(IZipArchive zipArchive,
                             IJsonSerialization jsonSerialization,
                             IRestInterfaceFactory restInterfaceFactory,
                             IRestBotAdministration restBotAdministration) {
        this.zipArchive = zipArchive;
        this.jsonSerialization = jsonSerialization;
        this.restInterfaceFactory = restInterfaceFactory;
        this.restBotAdministration = restBotAdministration;
    }

    @Override
    public List<BotDeploymentStatus> importBotExamples() {
        try (Stream<Path> walk = Files.walk(examplePath)) {
            walk.filter(file -> file.getFileName().toString().endsWith(".zip")).
                    forEach(path -> {
                        try {
                            importBot(new FileInputStream(path.toFile()), new MockAsyncResponse() {
                                @Override
                                public boolean resume(Object responseObj) {
                                    if (responseObj instanceof Response) {
                                        Response response = (Response) responseObj;
                                        IResourceId botId = RestUtilities.extractResourceId(response.getLocation());
                                        restBotAdministration.
                                                deployBot(
                                                        unrestricted,
                                                        botId.getId(),
                                                        botId.getVersion(),
                                                        true);
                                        return true;
                                    }

                                    return false;
                                }
                            });
                        } catch (FileNotFoundException e) {
                            log.error(e.getLocalizedMessage(), e);
                        }
                    });
            Thread.sleep(500);
            log.info("Imported & Deployed Example Bots");
            return restBotAdministration.getDeploymentStatuses(unrestricted);
        } catch (IOException | InterruptedException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public void importBot(InputStream zippedBotConfigFiles, AsyncResponse response) {
        try {
            if (response != null) response.setTimeout(60, TimeUnit.SECONDS);
            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
            importBotZipFile(zippedBotConfigFiles, targetDir, response);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            response.resume(new InternalServerErrorException());
        }
    }

    private void importBotZipFile(InputStream zippedBotConfigFiles, File targetDir, AsyncResponse response) throws IOException {
        this.zipArchive.unzip(zippedBotConfigFiles, targetDir);

        String targetDirPath = targetDir.getPath();
        Files.newDirectoryStream(Paths.get(targetDirPath),
                path -> path.toString().endsWith(BOT_FILE_ENDING))
                .forEach(botFilePath -> {
                    try {
                        String botFileString = readFile(botFilePath);
                        BotConfiguration botConfiguration =
                                jsonSerialization.deserialize(botFileString, BotConfiguration.class);
                        botConfiguration.getPackages().forEach(packageUri ->
                                parsePackage(targetDirPath, packageUri, botConfiguration, response));

                        URI newBotUri = createNewBot(botConfiguration);
                        updateDocumentDescriptor(Paths.get(targetDirPath), buildOldBotUri(botFilePath), newBotUri);
                        response.resume(Response.ok().location(newBotUri).build());
                    } catch (IOException | RestInterfaceFactory.RestInterfaceFactoryException e) {
                        log.error(e.getLocalizedMessage(), e);
                        response.resume(new InternalServerErrorException());
                    }
                });
    }

    private URI buildOldBotUri(Path botPath) {
        String botPathString = botPath.toString();
        String oldBotId = botPathString.substring(botPathString.lastIndexOf(File.separator) + 1,
                botPathString.lastIndexOf(BOT_FILE_ENDING));

        return URI.create(IRestBotStore.resourceURI + oldBotId + IRestBotStore.versionQueryParam + "1");
    }

    private void parsePackage(String targetDirPath, URI packageUri, BotConfiguration botConfiguration, AsyncResponse response) {
        try {
            IResourceId packageResourceId = RestUtilities.extractResourceId(packageUri);
            String packageId = packageResourceId.getId();
            String packageVersion = String.valueOf(packageResourceId.getVersion());

            Files.newDirectoryStream(Paths.get(FileUtilities.buildPath(targetDirPath, packageId, packageVersion)),
                    packageFilePath -> packageFilePath.toString().endsWith(".package.json")).
                    forEach(packageFilePath -> {
                        try {
                            Path packagePath = packageFilePath.getParent();
                            String packageFileString = readFile(packageFilePath);

                            // loading old resources, creating them in the new system,
                            // updating document descriptor and replacing references in package config

                            // ... for dictionaries
                            List<URI> dictionaryUris = extractResourcesUris(packageFileString, DICTIONARY_URI_PATTERN);
                            List<URI> newDictionaryUris = createNewDictionaries(
                                    readResources(dictionaryUris, packagePath,
                                            DICTIONARY_EXT, RegularDictionaryConfiguration.class));

                            updateDocumentDescriptor(packagePath, dictionaryUris, newDictionaryUris);
                            packageFileString = replaceURIs(packageFileString, dictionaryUris, newDictionaryUris);

                            // ... for behavior
                            List<URI> behaviorUris = extractResourcesUris(packageFileString, BEHAVIOR_URI_PATTERN);
                            List<URI> newBehaviorUris = createNewBehaviors(
                                    readResources(behaviorUris, packagePath,
                                            BEHAVIOR_EXT, BehaviorConfiguration.class));

                            updateDocumentDescriptor(packagePath, behaviorUris, newBehaviorUris);
                            packageFileString = replaceURIs(packageFileString, behaviorUris, newBehaviorUris);

                            // ... for http calls
                            List<URI> httpCallsUris = extractResourcesUris(packageFileString, HTTPCALLS_URI_PATTERN);
                            List<URI> newHttpCallsUris = createNewHttpCalls(
                                    readResources(httpCallsUris, packagePath,
                                            HTTPCALLS_EXT, HttpCallsConfiguration.class));

                            updateDocumentDescriptor(packagePath, httpCallsUris, newHttpCallsUris);
                            packageFileString = replaceURIs(packageFileString, httpCallsUris, newHttpCallsUris);

                            // ... for property
                            List<URI> propertyUris = extractResourcesUris(packageFileString, PROPERTY_URI_PATTERN);
                            List<URI> newPropertyUris = createNewProperties(
                                    readResources(propertyUris, packagePath,
                                            PROPERTY_EXT, PropertySetterConfiguration.class));

                            updateDocumentDescriptor(packagePath, propertyUris, newPropertyUris);
                            packageFileString = replaceURIs(packageFileString, propertyUris, newPropertyUris);

                            // ... for output
                            List<URI> outputUris = extractResourcesUris(packageFileString, OUTPUT_URI_PATTERN);
                            List<URI> newOutputUris = createNewOutputs(
                                    readResources(outputUris, packagePath,
                                            OUTPUT_EXT, OutputConfigurationSet.class));

                            updateDocumentDescriptor(packagePath, outputUris, newOutputUris);
                            packageFileString = replaceURIs(packageFileString, outputUris, newOutputUris);

                            // creating updated package and replacing references in bot config
                            URI newPackageUri = createNewPackage(packageFileString);
                            updateDocumentDescriptor(packagePath, packageUri, newPackageUri);
                            botConfiguration.setPackages(botConfiguration.getPackages().stream().
                                    map(uri -> uri.equals(packageUri) ? newPackageUri : uri).
                                    collect(Collectors.toList()));

                        } catch (IOException | RestInterfaceFactory.RestInterfaceFactoryException |
                                CallbackMatcher.CallbackMatcherException e) {
                            log.error(e.getLocalizedMessage(), e);
                            response.resume(new InternalServerErrorException());
                        }
                    });
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            response.resume(new InternalServerErrorException());
        }
    }

    private URI createNewBot(BotConfiguration botConfiguration)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestBotStore restPackageStore = getRestResourceStore(IRestBotStore.class);
        Response botResponse = restPackageStore.createBot(botConfiguration);
        checkIfCreatedResponse(botResponse);
        return botResponse.getLocation();
    }

    private URI createNewPackage(String packageFileString)
            throws RestInterfaceFactory.RestInterfaceFactoryException, IOException {
        PackageConfiguration packageConfiguration =
                jsonSerialization.deserialize(packageFileString, PackageConfiguration.class);
        IRestPackageStore restPackageStore = getRestResourceStore(IRestPackageStore.class);
        Response packageResponse = restPackageStore.createPackage(packageConfiguration);
        checkIfCreatedResponse(packageResponse);
        return packageResponse.getLocation();
    }

    private List<URI> createNewDictionaries(List<RegularDictionaryConfiguration> dictionaryConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestRegularDictionaryStore restDictionaryStore = getRestResourceStore(IRestRegularDictionaryStore.class);
        return dictionaryConfigurations.stream().map(regularDictionaryConfiguration -> {
            Response dictionaryResponse = restDictionaryStore.createRegularDictionary(regularDictionaryConfiguration);
            checkIfCreatedResponse(dictionaryResponse);
            return dictionaryResponse.getLocation();
        }).collect(Collectors.toList());
    }

    private List<URI> createNewBehaviors(List<BehaviorConfiguration> behaviorConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestBehaviorStore restBehaviorStore = getRestResourceStore(IRestBehaviorStore.class);
        return behaviorConfigurations.stream().map(behaviorConfiguration -> {
            Response behaviorResponse = restBehaviorStore.createBehaviorRuleSet(behaviorConfiguration);
            checkIfCreatedResponse(behaviorResponse);
            return behaviorResponse.getLocation();
        }).collect(Collectors.toList());
    }

    private List<URI> createNewHttpCalls(List<HttpCallsConfiguration> httpCallsConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestHttpCallsStore restHttpCallsStore = getRestResourceStore(IRestHttpCallsStore.class);
        return httpCallsConfigurations.stream().map(httpCallsConfiguration -> {
            Response httpCallsResponse = restHttpCallsStore.createHttpCalls(httpCallsConfiguration);
            checkIfCreatedResponse(httpCallsResponse);
            return httpCallsResponse.getLocation();
        }).collect(Collectors.toList());
    }

    private List<URI> createNewProperties(List<PropertySetterConfiguration> propertySetterConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestPropertySetterStore restPropertySetterStore = getRestResourceStore(IRestPropertySetterStore.class);
        return propertySetterConfigurations.stream().map(propertySetterConfiguration -> {
            Response propertySetter = restPropertySetterStore.createPropertySetter(propertySetterConfiguration);
            checkIfCreatedResponse(propertySetter);
            return propertySetter.getLocation();
        }).collect(Collectors.toList());
    }

    private List<URI> createNewOutputs(List<OutputConfigurationSet> outputConfigurations)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        IRestOutputStore restOutputStore = getRestResourceStore(IRestOutputStore.class);
        return outputConfigurations.stream().map(outputConfiguration -> {
            Response outputResponse = restOutputStore.createOutputSet(outputConfiguration);
            checkIfCreatedResponse(outputResponse);
            return outputResponse.getLocation();
        }).collect(Collectors.toList());
    }

    private void updateDocumentDescriptor(Path directoryPath, URI oldUri, URI newUri)
            throws RestInterfaceFactory.RestInterfaceFactoryException {
        updateDocumentDescriptor(directoryPath, Collections.singletonList(oldUri), Collections.singletonList(newUri));
    }

    private void updateDocumentDescriptor(Path directoryPath, List<URI> oldUris, List<URI> newUris)
            throws RestInterfaceFactory.RestInterfaceFactoryException {

        IRestDocumentDescriptorStore restDocumentDescriptorStore = getRestResourceStore(IRestDocumentDescriptorStore.class);
        IntStream.range(0, oldUris.size()).forEach(idx -> {
            try {
                URI oldUri = oldUris.get(idx);
                IResourceId oldResourceId = RestUtilities.extractResourceId(oldUri);
                DocumentDescriptor oldDocumentDescriptor = readDocumentDescriptorFromFile(directoryPath, oldResourceId);

                URI newUri = newUris.get(idx);
                IResourceId newResourceId = RestUtilities.extractResourceId(newUri);

                PatchInstruction<DocumentDescriptor> patchInstruction = new PatchInstruction<>();
                patchInstruction.setOperation(PatchInstruction.PatchOperation.SET);
                patchInstruction.setDocument(oldDocumentDescriptor);

                restDocumentDescriptorStore.patchDescriptor(newResourceId.getId(), newResourceId.getVersion(), patchInstruction);
            } catch (IOException e) {
                log.error(e.getLocalizedMessage(), e);
            }
        });
    }

    private DocumentDescriptor readDocumentDescriptorFromFile(Path packagePath, IResourceId resourceId)
            throws IOException {
        Path filePath = Paths.get(FileUtilities.buildPath(packagePath.toString(), resourceId.getId() + ".descriptor.json"));
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

    private <T> List<T> readResources(List<URI> uris, Path packagePath, String extension, Class<T> clazz) {
        return uris.stream().map(uri -> {
            Path resourcePath = null;
            String resourceContent = null;
            try {
                IResourceId resourceId = RestUtilities.extractResourceId(uri);
                resourcePath = createResourcePath(packagePath, resourceId.getId(), extension);
                resourceContent = readFile(resourcePath);
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
            log.error(String.format("Http Response Code was not 201 when attempting resource creation, but %s", status));
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
