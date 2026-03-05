package ai.labs.eddi.configs.behavior.mongo;

import ai.labs.eddi.configs.behavior.IBehaviorStore;
import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorGroupConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConfiguration;
import ai.labs.eddi.datastore.mongo.AbstractMongoResourceStore;
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
public class BehaviorStore extends AbstractMongoResourceStore<BehaviorConfiguration>
        implements IBehaviorStore {

    @Inject
    public BehaviorStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        super(database, "behaviorrulesets", documentBuilder, BehaviorConfiguration.class);
    }

    @Override
    public IResourceId create(BehaviorConfiguration behaviorConfiguration) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        return super.create(behaviorConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public synchronized Integer update(String id, Integer version, BehaviorConfiguration behaviorConfiguration)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {

        RuntimeUtilities.checkCollectionNoNullElements(behaviorConfiguration.getBehaviorGroups(), "behaviorGroups");
        return super.update(id, version, behaviorConfiguration);
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws ResourceStoreException, ResourceNotFoundException {

        List<String> actions = read(id, version).getBehaviorGroups().stream()
                .map(BehaviorGroupConfiguration::getBehaviorRules).flatMap(Collection::stream)
                .map(BehaviorRuleConfiguration::getActions).flatMap(Collection::stream).collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, limit) : actions;
    }
}
