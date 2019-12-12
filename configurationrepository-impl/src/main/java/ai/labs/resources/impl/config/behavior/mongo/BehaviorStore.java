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
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

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
    public BehaviorConfiguration readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
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
    public synchronized Integer update(String id, Integer version, BehaviorConfiguration behaviorConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        return behaviorResourceStore.update(id, version, behaviorConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
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
    public List<String> readBehaviorRuleNames(String id, Integer version, String filter, String order, Integer limit) throws ResourceStoreException, ResourceNotFoundException {
        List<String> retBehaviorRuleNames = new LinkedList<>();
        BehaviorConfiguration behaviorConfiguration = read(id, version);
        for (BehaviorGroupConfiguration groupConfiguration : behaviorConfiguration.getBehaviorGroups()) {
            List<BehaviorRuleConfiguration> behaviorRules = groupConfiguration.getBehaviorRules();
            for (BehaviorRuleConfiguration behaviorRule : behaviorRules) {
                String name = behaviorRule.getName();
                if (name.contains(filter)) {
                    retBehaviorRuleNames.add(name);
                    if (retBehaviorRuleNames.size() >= limit) {
                        break;
                    }
                }
            }
        }

        if ("asc".equals(order)) {
            Collections.sort(retBehaviorRuleNames);
        } else {
            Collections.sort(retBehaviorRuleNames, Collections.reverseOrder());
        }

        return retBehaviorRuleNames;
    }
}
