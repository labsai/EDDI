package ai.labs.resources.impl.behavior.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.behavior.IBehaviorStore;
import ai.labs.resources.rest.behavior.IRestBehaviorStore;
import ai.labs.resources.rest.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestBehaviorStore extends RestVersionInfo<BehaviorConfiguration> implements IRestBehaviorStore {
    private final IBehaviorStore behaviorStore;

    @Inject
    public RestBehaviorStore(IBehaviorStore behaviorStore,
                             IDocumentDescriptorStore documentDescriptorStore) {
        super(resourceURI, behaviorStore, documentDescriptorStore);
        this.behaviorStore = behaviorStore;
    }

    @Override
    public List<DocumentDescriptor> readBehaviorDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.behavior", filter, index, limit);
    }

    @Override
    public BehaviorConfiguration readBehaviorRuleSet(String id, Integer version) {
        return read(id, version);
    }

    @Override
    public Response updateBehaviorRuleSet(String id, Integer version, BehaviorConfiguration behaviorConfiguration) {
        return update(id, version, behaviorConfiguration);
    }

    @Override
    public Response createBehaviorRuleSet(BehaviorConfiguration behaviorConfiguration) {
        return create(behaviorConfiguration);
    }

    @Override
    public Response deleteBehaviorRuleSet(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return behaviorStore.getCurrentResourceId(id);
    }
}
