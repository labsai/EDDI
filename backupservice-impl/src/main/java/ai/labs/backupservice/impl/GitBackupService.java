package ai.labs.backupservice.impl;

import ai.labs.backupservice.IGitBackupService;
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
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.nio.file.Path;
import java.nio.file.Paths;

import static ai.labs.models.Deployment.Environment.unrestricted;

/**
 * @author rpi
 */
@Slf4j
public class GitBackupService implements IGitBackupService {
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IBotStore botStore;
    private final IPackageStore packageStore;
    private final IRegularDictionaryStore regularDictionaryStore;
    private final IBehaviorStore behaviorStore;
    private final IHttpCallsStore httpCallsStore;
    private final IOutputStore outputStore;
    private final IJsonSerialization jsonSerialization;
    private final Path tmpPath = Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), "tmp"));

    @Inject
    public GitBackupService(IDocumentDescriptorStore documentDescriptorStore,
                             IBotStore botStore,
                             IPackageStore packageStore,
                             IRegularDictionaryStore regularDictionaryStore,
                             IBehaviorStore behaviorStore,
                             IHttpCallsStore httpCallsStore,
                             IOutputStore outputStore,
                             IJsonSerialization jsonSerialization) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.botStore = botStore;
        this.packageStore = packageStore;
        this.regularDictionaryStore = regularDictionaryStore;
        this.behaviorStore = behaviorStore;
        this.httpCallsStore = httpCallsStore;
        this.outputStore = outputStore;
        this.jsonSerialization = jsonSerialization;
    }





    @Override
    public Response gitPull(String botid, boolean force) {
        try {
            IResourceStore.IResourceId resourceId =  botStore.getCurrentResourceId(botid);
            BotConfiguration botConfiguration = botStore.read(botid, resourceId.getVersion());
            BotConfiguration.GitBackupSettings gitBackupSettings = botConfiguration.getGitBackupSettings();
            if (gitBackupSettings != null) {
                Git.cloneRepository()
                    .setBranch(gitBackupSettings.getBranch())
                    .setURI(gitBackupSettings.getRepositoryUrl().toString())
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(gitBackupSettings.getUsername(), gitBackupSettings.getPassword()))
                    .setDirectory(tmpPath.toFile())
                    .call();
            } else {
                return Response.status(Response.Status.NOT_FOUND).entity("No git settings in bot configuration, please add git settings!").build();
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).build();
        } catch (IResourceStore.ResourceStoreException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        } catch (InvalidRemoteException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (GitAPIException e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }

        return null;
    }

    @Override
    public Response gitCommit(String botid) {
        return null;
    }

    @Override
    public Response gitPush(String botid) {
        return null;
    }


}
