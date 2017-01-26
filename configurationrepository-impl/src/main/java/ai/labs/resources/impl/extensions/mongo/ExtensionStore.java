package ai.labs.resources.impl.extensions.mongo;

import ai.labs.group.IGroupStore;
import ai.labs.permission.IPermissionStore;
import ai.labs.persistence.IResourceFilter;
import ai.labs.persistence.ResourceFilter;
import ai.labs.persistence.mongo.ModifiableHistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.extensions.IExtensionStore;
import ai.labs.resources.rest.extensions.model.ExtensionDefinition;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.user.IUserStore;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public class ExtensionStore implements IExtensionStore {
    private static final String COLLECTION_EXTENSIONS = "extensions";
    private DBCollection extensionCollection;
    private ModifiableHistorizedResourceStore<ExtensionDefinition> extensionResourceStore;
    private IResourceFilter<ExtensionDefinition> resourceFilter;

    @Inject
    public ExtensionStore(DB database,
                          IDocumentBuilder documentBuilder,
                          IPermissionStore permissionStore,
                          IGroupStore groupStore,
                          IUserStore userStore) {
        RuntimeUtilities.checkNotNull(database, "database");

        extensionCollection = database.getCollection(COLLECTION_EXTENSIONS);
        MongoResourceStorage<ExtensionDefinition> resourceStorage =
                new MongoResourceStorage<>(database, COLLECTION_EXTENSIONS, documentBuilder, ExtensionDefinition.class);
        this.extensionResourceStore = new ModifiableHistorizedResourceStore<>(resourceStorage);
        this.resourceFilter = new ResourceFilter<>(extensionCollection, extensionResourceStore, permissionStore,
                userStore, groupStore, documentBuilder, ExtensionDefinition.class);
    }

    @Override
    public IResourceId searchExtension(String uri) {
        final DBObject document = extensionCollection.findOne(new BasicDBObject("type", uri));

        if (document == null) {
            return null;
        }

        return new IResourceId() {
            @Override
            public String getId() {
                return document.get("_id").toString();
            }

            @Override
            public Integer getVersion() {
                return Integer.parseInt(document.get("_version").toString());
            }
        };
    }

    @Override
    public List<ExtensionDefinition> readExtensions(String filter, Integer index, Integer limit) throws ResourceNotFoundException, ResourceStoreException {
        filter = "^core://" + filter + ".?[a-zA-Z0-9]*$";
        IResourceFilter.QueryFilter queryFilter = new IResourceFilter.QueryFilter("type", filter);
        IResourceFilter.QueryFilters queryFilters = new IResourceFilter.QueryFilters(Collections.singletonList(queryFilter));
        return resourceFilter.readResources(new IResourceFilter.QueryFilters[]{queryFilters}, index, limit);
    }

    @Override
    public IResourceId create(ExtensionDefinition content) throws ResourceStoreException {
        if (searchExtension(content.getType().toString()) != null) {
            String message = "Cannot get extension definition. Definition with type already exists. [type=%s]";
            message = String.format(message, content.getType());
            throw new ResourceStoreException(message);
        }

        return extensionResourceStore.create(content);
    }

    @Override
    public ExtensionDefinition read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return extensionResourceStore.read(id, version);
    }

    @Override
    public Integer update(String id, Integer version, ExtensionDefinition content) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        return extensionResourceStore.update(id, version, content);
    }

    @Override
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        extensionResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        extensionResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return extensionResourceStore.getCurrentResourceId(id);
    }
}
