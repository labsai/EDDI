package io.sls.persistence.impl.behavior.mongo;

import com.mongodb.DB;
import io.sls.persistence.impl.mongo.HistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.resources.rest.behavior.IBehaviorStore;
import io.sls.resources.rest.behavior.model.BehaviorConfiguration;
import io.sls.resources.rest.behavior.model.BehaviorGroupConfiguration;
import io.sls.resources.rest.behavior.model.BehaviorRuleConfiguration;
import io.sls.serialization.IDocumentBuilder;
import io.sls.utilities.RuntimeUtilities;

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
    public BehaviorStore(DB database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");

        MongoResourceStorage<BehaviorConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, BehaviorConfiguration.class);

        this.behaviorResourceStore = new HistorizedResourceStore<>(resourceStorage);
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
    public synchronized Integer update(String id, Integer version, BehaviorConfiguration behaviorConfiguration) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        return behaviorResourceStore.update(id, version, behaviorConfiguration);
    }

    @Override
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
