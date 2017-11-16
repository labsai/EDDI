package ai.labs.backupservice.impl;

import ai.labs.backupservice.IRestImportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.FileUtilities;
import ai.labs.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

/**
 * @author ginccc
 */
@Slf4j
public class RestImportService extends AbstractBackupService implements IRestImportService {
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp", "import"));
    private final IZipArchive zipArchive;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public RestImportService(IZipArchive zipArchive,
                             IJsonSerialization jsonSerialization) {
        this.zipArchive = zipArchive;
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public Response importBot(InputStream zippedBotConfigFiles) {
        try {

            File targetDir = new File(FileUtilities.buildPath(tmpPath.toString(), UUID.randomUUID().toString()));
            this.zipArchive.unzip(zippedBotConfigFiles, targetDir);

            String targetDirPath = targetDir.getPath();
            Files.newDirectoryStream(Paths.get(targetDirPath),
                    path -> path.toString().endsWith(".bot.json"))
                    .forEach(path -> {
                        try {
                            String botFileString = readFile(path);
                            BotConfiguration botConfiguration = jsonSerialization.deserialize(botFileString, BotConfiguration.class);
                            botConfiguration.getPackages().forEach(uri ->
                            {
                                IResourceStore.IResourceId packageResourceId = RestUtilities.extractResourceId(uri);
                                String packageId = packageResourceId.getId();
                                String packageVersion = String.valueOf(packageResourceId.getVersion());

                                parsePackage(targetDirPath, packageId, packageVersion);
                            });
                        } catch (IOException e) {
                            log.error(e.getLocalizedMessage(), e);
                        }
                    });

            return Response.created(URI.create("")).build();

        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    private void parsePackage(String targetDirPath, String packageId, String packageVersion) {
        try {
            Files.newDirectoryStream(Paths.get(FileUtilities.buildPath(targetDirPath, packageId, packageVersion)),
                    path -> path.toString().endsWith(".package.json")).
                    forEach(path -> {
                        try {
                            String packageFileString = readFile(path);
                            PackageConfiguration packageConfiguration = jsonSerialization.deserialize(packageFileString, PackageConfiguration.class);
                            List<URI> dictionaryUris = super.extractRegularDictionaries(packageConfiguration);
                            List<URI> behaviorUris = super.extractResources(packageConfiguration, BEHAVIOR_URI);
                            List<URI> outputUris = super.extractResources(packageConfiguration, OUTPUT_URI);

                            //TODO: read and create all dictionaries, behaviors and outputs + descriptors
                            // todo then substitute all new IDs within packageConfig, save packageConfig,
                            // todo then substitute package IDs in bot, then create bot

                        } catch (IOException e) {
                            log.error(e.getLocalizedMessage(), e);
                        }
                    });
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    private String readFile(Path path) throws FileNotFoundException {
        StringBuilder ret = new StringBuilder();
        try (Scanner scanner = new Scanner(new File(path.toString()))) {
            while (scanner.hasNext()) {
                ret.append(scanner.nextLine());
            }
        }

        return ret.toString();
    }
}
