package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IRestExportService;
import ai.labs.eddi.backup.IRestGitBackupService;
import ai.labs.eddi.backup.IRestImportService;
import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.backup.IGitBackupStore;
import ai.labs.eddi.configs.backup.model.GitBackupSettings;
import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.FileUtilities;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FS;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_BRANCH_SECTION;

/**
 * @author rpi
 */

@ApplicationScoped
public class RestGitBackupService implements IRestGitBackupService {
    private final IBotStore botStore;
    private final String tmpPath = System.getProperty("user.dir") + "/tmp/";
    private final IZipArchive zipArchive;
    private final IRestImportService importService;
    private final IRestExportService exportService;
    private final String gitUsername;
    private final String gitPassword;
    private final String gitUrl;
    private final String gitBranch;
    private final boolean gitAutomatic;
    private final String gitCommiterName;
    private final String gitCommiterEmail;

    private static final Logger log = Logger.getLogger(RestGitBackupService.class);

    @Inject
    public RestGitBackupService(IBotStore botStore,
                                IGitBackupStore backupStore,
                                IZipArchive zipArchive,
                                IRestImportService importService,
                                IRestExportService exportService) {
        this.botStore = botStore;
        this.zipArchive = zipArchive;
        this.importService = importService;
        this.exportService = exportService;

        GitBackupSettings settings = backupStore.readSettingsInternal();
        this.gitUsername = settings.getUsername();
        this.gitPassword = settings.getPassword();
        this.gitUrl = settings.getRepositoryUrl();
        this.gitBranch = settings.getBranch();
        this.gitAutomatic = settings.isAutomatic();
        this.gitCommiterName = settings.getCommitterName();
        this.gitCommiterEmail = settings.getCommitterEmail();

    }

    @Override
    public Response gitInit(String botId) {
        try {
            deleteFileIfExists(Paths.get(tmpPath + botId));
            Path gitPath = Files.createDirectories(Paths.get(tmpPath + botId));
            Git git = Git.cloneRepository()
                    .setBranch(gitBranch)
                    .setURI(gitUrl)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitUsername, gitPassword))
                    .setDirectory(gitPath.toFile())
                    .call();
            StoredConfig config = git.getRepository().getConfig();
            config.setString(CONFIG_BRANCH_SECTION, "local-branch", "remote", gitBranch);
            config.setString(CONFIG_BRANCH_SECTION, "local-branch", "merge", "refs/heads/" + gitBranch);
            config.save();

        } catch (IOException | GitAPIException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
        return Response.accepted().build();
    }

    @Override
    public Response gitPull(String botId, boolean force) {
        try {
            IResourceStore.IResourceId resourceId = botStore.getCurrentResourceId(botId);
            Path gitPath = Paths.get(tmpPath + botId);

            if (gitUrl != null) {
                PullResult pullResult = Git.open(gitPath.toFile())
                        .pull()
                        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitUsername, gitPassword))
                        .call();
                if (pullResult.isSuccessful()) {
                    importBot(botId, resourceId.getVersion());
                    return Response.status(Response.Status.OK).entity(pullResult.toString()).build();
                } else {
                    return Response.status(Response.Status.OK).entity("Pull from repository was not successful! Please check your git settings! Maybe the path " + tmpPath + " is not empty or not a git repository").build();
                }
            }

            return Response.status(Response.Status.OK).build();
        } catch (IResourceStore.ResourceNotFoundException | InvalidRemoteException e) {
            return Response.status(Response.Status.NOT_FOUND).entity("pull from configured repository failed - repository was not found, please check your settings").build();
        } catch (GitAPIException | IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public Response gitCommit(String botId, String commitMessage) {
        try {
            IResourceStore.IResourceId resourceId = botStore.getCurrentResourceId(botId);
            Path gitPath = Paths.get(tmpPath + botId);
            if (gitUrl != null) {
                exportService.exportBot(botId, resourceId.getVersion());
                Git.open(gitPath.toFile())
                        .add()
                        .addFilepattern(".")
                        .call();
                RevCommit commit = Git.open(gitPath.toFile())
                        .commit()
                        .setMessage(commitMessage)
                        .setCommitter(gitCommiterName, gitCommiterEmail)
                        .call();
                return Response.status(Response.Status.OK).entity(commit.getFullMessage()).build();
            } else {
                return Response.status(Response.Status.BAD_REQUEST).entity("Git repo not initialized, please call gitInit first").build();
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity("commit failed - bot id is incorrect").build();
        } catch (IOException | GitAPIException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }

    }

    @Override
    public Response gitPush(String botId) {
        try {
            IResourceStore.IResourceId resourceId = botStore.getCurrentResourceId(botId);
            Path gitPath = Paths.get(tmpPath + botId);

            if (gitUrl != null) {
                Iterable<PushResult> pushResults = Git.open(gitPath.toFile())
                        .push()
                        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitUsername, gitPassword))
                        .call();
                StringBuilder pushResultMessage = new StringBuilder();
                for (PushResult pushResult : pushResults) {
                    pushResultMessage.append(pushResult.getMessages());
                }
                return Response.status(Response.Status.OK).entity(pushResultMessage).build();
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("No git settings in bot configuration, please add git settings!").build();
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity("push failed - bot id was not found").build();
        } catch (IOException | GitAPIException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    private String prepareZipFilename(String botId, Integer botVersion) {
        return botId + "-" + botVersion + ".zip";
    }


    private void deleteFileIfExists(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } else {
            if (Files.exists(path)) {
                Files.delete(path);
            }
        }
    }


    private void importBot(String botId, Integer version) throws IOException {
        String zipFilename = prepareZipFilename(botId, version);
        String targetZipPath = FileUtilities.buildPath(tmpPath, zipFilename);
        deleteFileIfExists(Paths.get(targetZipPath));
        this.zipArchive.createZip(tmpPath, targetZipPath);
        importService.importBot(new FileInputStream(targetZipPath), null);
    }

    public boolean isGitAutomatic() {
        return gitAutomatic;
    }

    public boolean isGitInitialised(String botId) {
        Path gitPath = Paths.get(tmpPath + botId);
        return RepositoryCache.FileKey.isGitRepository(gitPath.toFile(), FS.DETECTED);
    }
}
