package ai.labs.eddi.configs.behavior.mongo;

import ai.labs.eddi.configs.behavior.IBehaviorStore;
import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorGroupConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoDatabase;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@ApplicationScoped
public class BehaviorStore implements IBehaviorStore {

    private final HistorizedResourceStore<BehaviorConfiguration> behaviorResourceStore;
    private final String collectionName = "behaviorrulesets";

    @Inject
    public BehaviorStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");

        MongoResourceStorage<BehaviorConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, BehaviorConfiguration.class);

        this.behaviorResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public BehaviorConfiguration readIncludingDeleted(String id, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        return behaviorResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(BehaviorConfiguration behaviorConfiguration) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        return behaviorResourceStore.create(behaviorConfiguration);
    }

    @Override
    public BehaviorConfiguration read(String id, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return behaviorResourceStore.read(id, version);
    }


    @Override
    @ConfigurationUpdate
    public synchronized Integer update(String id, Integer version, BehaviorConfiguration behaviorConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {

        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        return behaviorResourceStore.update(id, version, behaviorConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        behaviorResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        behaviorResourceStore.deleteAllPermanently(id);
    }


    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return behaviorResourceStore.getCurrentResourceId(id);
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        List<String> actions = read(id, version).getBehaviorGroups().stream().
                map(BehaviorGroupConfiguration::getBehaviorRules).
                flatMap(Collection::stream).
                map(BehaviorRuleConfiguration::getActions).
                flatMap(Collection::stream).
                collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, limit) : actions;
    }
}
