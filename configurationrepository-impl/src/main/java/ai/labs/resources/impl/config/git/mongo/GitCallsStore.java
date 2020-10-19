package ai.labs.resources.impl.config.git.mongo;

import ai.labs.persistence.IResourceStore;
import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.config.git.IGitCallsStore;
import ai.labs.resources.rest.config.git.model.GitCallsConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;


/**
 * @author rpi
 */

public class GitCallsStore implements IGitCallsStore {
    private HistorizedResourceStore<GitCallsConfiguration> gitCallsResourceStore;

    @Inject
    public GitCallsStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "gitcalls";
        MongoResourceStorage<GitCallsConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, GitCallsConfiguration.class);
        this.gitCallsResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public GitCallsConfiguration readIncludingDeleted(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return gitCallsResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(GitCallsConfiguration gitCallsConfiguration) throws IResourceStore.ResourceStoreException {
        return gitCallsResourceStore.create(gitCallsConfiguration);
    }

    @Override
    public GitCallsConfiguration read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return gitCallsResourceStore.read(id, version);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, GitCallsConfiguration gitCallsConfiguration) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        return gitCallsResourceStore.update(id, version, gitCallsConfiguration);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public void delete(String id, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        gitCallsResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        gitCallsResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return gitCallsResourceStore.getCurrentResourceId(id);
    }

}
