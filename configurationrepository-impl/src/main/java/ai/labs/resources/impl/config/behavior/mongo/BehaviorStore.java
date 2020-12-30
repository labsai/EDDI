package ai.labs.resources.impl.config.behavior.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.config.behavior.IBehaviorStore;
import ai.labs.resources.rest.config.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.config.behavior.model.BehaviorGroupConfiguration;
import ai.labs.resources.rest.config.behavior.model.BehaviorRuleConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
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
            throws ResourceNotFoundException, ResourceStoreException {

        return behaviorResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(BehaviorConfiguration behaviorConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        return behaviorResourceStore.create(behaviorConfiguration);
    }

    @Override
    public BehaviorConfiguration read(String id, Integer version) throws ResourceStoreException, ResourceNotFoundException {
        return behaviorResourceStore.read(id, version);
    }


    @Override
    @ConfigurationUpdate
    public synchronized Integer update(String id, Integer version, BehaviorConfiguration behaviorConfiguration)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {

        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        return behaviorResourceStore.update(id, version, behaviorConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceModifiedException, ResourceNotFoundException {
        behaviorResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        behaviorResourceStore.deleteAllPermanently(id);
    }


    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return behaviorResourceStore.getCurrentResourceId(id);
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws ResourceStoreException, ResourceNotFoundException {

        List<String> actions = read(id, version).getBehaviorGroups().stream().
                map(BehaviorGroupConfiguration::getBehaviorRules).
                flatMap(Collection::stream).
                map(BehaviorRuleConfiguration::getActions).
                flatMap(Collection::stream).
                collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, limit) : actions;
    }
}
