package ai.labs.backupservice.impl;

import ai.labs.backupservice.IGitBackupService;
import ai.labs.backupservice.IRestExportService;
import ai.labs.backupservice.IRestImportService;
import ai.labs.backupservice.IZipArchive;
import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.behavior.IBehaviorStore;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.model.BotConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.http.IHttpCallsStore;
import ai.labs.resources.rest.output.IOutputStore;
import ai.labs.resources.rest.packages.IPackageStore;
import ai.labs.resources.rest.regulardictionary.IRegularDictionaryStore;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.FileUtilities;
import ai.labs.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.List;

import static ai.labs.models.Deployment.Environment.unrestricted;

/**
 * @author rpi
 */
@Slf4j
public class GitBackupService implements IGitBackupService {
    private final IBotStore botStore;
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));
    private final IZipArchive zipArchive;
    private final IRestImportService importService;
    private final IRestExportService exportService;

    @Inject
    public GitBackupService(IBotStore botStore,
                            IZipArchive zipArchive,
                            IRestImportService importService,
                            IRestExportService exportService) {
        this.botStore = botStore;
        this.zipArchive = zipArchive;
        this.importService = importService;
        this.exportService = exportService;
    }


    @Override
    public Response gitPull(String botid, boolean force) {
        try {
            createDirIfNotExists(tmpPath);
            IResourceStore.IResourceId resourceId =  botStore.getCurrentResourceId(botid);
            BotConfiguration botConfiguration = botStore.read(botid, resourceId.getVersion());
            BotConfiguration.GitBackupSettings gitBackupSettings = botConfiguration.getGitBackupSettings();

            if (gitBackupSettings != null && gitBackupSettings.getRepositoryUrl() != null) {
                if (isDirEmpty(tmpPath)) {
                    Git.cloneRepository()
                            .setBranch(gitBackupSettings.getBranch())
                            .setURI(gitBackupSettings.getRepositoryUrl().toString())
                            .setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitBackupSettings.getUsername(), gitBackupSettings.getPassword()))
                            .setDirectory(tmpPath.toFile())
                            .call();
                    importBot(botid, resourceId.getVersion());
                } else {
                    PullResult pullResult = Git.open(tmpPath.toFile())
                            .pull()
                            .call();
                    if (pullResult.isSuccessful()) {
                        importBot(botid, resourceId.getVersion());
                        return Response.status(Response.Status.OK).entity("Pulled from: " + pullResult.getFetchedFrom()+ ". Was successfull!").build();
                    } else {
                        return Response.status(Response.Status.OK).entity("Pull from repository was not successfull! Please check your git settings! Maybe the path " + tmpPath.toString() + " is not empty or not a git repository").build();
                    }
                }

                return Response.status(Response.Status.OK).build();
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("No git settings in bot configuration, please add git settings!").build();
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        } catch (IResourceStore.ResourceStoreException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        } catch (InvalidRemoteException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (GitAPIException | IOException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    @Override
    public Response gitCommit(String botid, String commitMessage) {
        try {
            createDirIfNotExists(tmpPath);
            IResourceStore.IResourceId resourceId =  botStore.getCurrentResourceId(botid);
            BotConfiguration botConfiguration = botStore.read(botid, resourceId.getVersion());
            BotConfiguration.GitBackupSettings gitBackupSettings = botConfiguration.getGitBackupSettings();
            if (gitBackupSettings != null && gitBackupSettings.getRepositoryUrl() != null) {
                if (isDirEmpty(tmpPath)) {
                    Git.cloneRepository()
                            .setBranch(gitBackupSettings.getBranch())
                            .setURI(gitBackupSettings.getRepositoryUrl().toString())
                            .setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitBackupSettings.getUsername(), gitBackupSettings.getPassword()))
                            .setDirectory(tmpPath.toFile())
                            .call();
                    exportService.exportBot(botid, resourceId.getVersion());
                    RevCommit commit = Git.open(tmpPath.toFile())
                            .commit()
                            .setMessage(commitMessage)
                            .setCommitter("EDDI", "eddi@labs.ai")
                            .call();
                    return Response.status(Response.Status.OK).entity(commit.getFullMessage()).build();
                } else {
                    RevCommit commit = Git.open(tmpPath.toFile())
                            .commit()
                            .setMessage(commitMessage)
                            .setCommitter("EDDI", "eddi@labs.ai")
                            .call();
                    return Response.status(Response.Status.OK).entity(commit.getFullMessage()).build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("No git settings in bot configuration, please add git settings!").build();
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        } catch (IResourceStore.ResourceStoreException | IOException | GitAPIException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }

    }

    @Override
    public Response gitPush(String botid) {
        try {
            createDirIfNotExists(tmpPath);
            IResourceStore.IResourceId resourceId =  botStore.getCurrentResourceId(botid);
            BotConfiguration botConfiguration = botStore.read(botid, resourceId.getVersion());
            BotConfiguration.GitBackupSettings gitBackupSettings = botConfiguration.getGitBackupSettings();
            if (gitBackupSettings != null && gitBackupSettings.getRepositoryUrl() != null) {
                if (isDirEmpty(tmpPath)) {
                    return Response.status(Response.Status.NOT_FOUND).entity("The repository is in the directory " + tmpPath.toString() + " was empty! Please use commit or pull before pushing!").build();
                } else {
                    Iterable<PushResult> pushResults = Git.open(tmpPath.toFile())
                            .push()
                            .call();
                    StringBuilder pushResultMessage = new StringBuilder();
                    for (PushResult pushResult : pushResults) {
                        pushResultMessage.append(pushResult.getMessages());
                    }
                    return Response.status(Response.Status.OK).entity(pushResultMessage.toString()).build();
                }
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("No git settings in bot configuration, please add git settings!").build();
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        } catch (IResourceStore.ResourceStoreException | IOException | GitAPIException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    private String prepareZipFilename(String botId, Integer botVersion) {
        return botId + "-" + botVersion + ".zip";
    }


    private void deleteFileIfExists(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.delete(path);
        }
    }

    private void createDirIfNotExists(final Path tmpPath) throws IOException {
        if (!Files.exists(tmpPath)) {
            Files.createDirectory(tmpPath);
        }
    }

    private boolean isDirEmpty(final Path directory) throws IOException {
        try(DirectoryStream<Path> dirStream = Files.newDirectoryStream(directory)) {
            return !dirStream.iterator().hasNext();
        }
    }

    private void importBot(String botid, Integer version) throws IOException {
        String zipFilename = prepareZipFilename(botid, version);
        String targetZipPath = FileUtilities.buildPath(tmpPath.toString(), zipFilename);
        deleteFileIfExists(Paths.get(targetZipPath));
        this.zipArchive.createZip(tmpPath.toString(), targetZipPath);
        importService.importBot(new FileInputStream(targetZipPath), null);
    }

}
