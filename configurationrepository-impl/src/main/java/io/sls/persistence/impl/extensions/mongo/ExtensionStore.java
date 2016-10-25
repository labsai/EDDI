package io.sls.persistence.impl.extensions.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import io.sls.group.IGroupStore;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.IResourceFilter;
import io.sls.persistence.impl.ResourceFilter;
import io.sls.persistence.impl.mongo.ModifiableHistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.resources.rest.extensions.IExtensionStore;
import io.sls.resources.rest.extensions.model.ExtensionDefinition;
import io.sls.serialization.IDocumentBuilder;
import io.sls.serialization.JSONSerialization;
import io.sls.user.IUserStore;
import io.sls.utilities.RuntimeUtilities;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * @author ginccc
 */
public class ExtensionStore implements IExtensionStore {
    private static final String COLLECTION_EXTENSIONS = "extensions";
    private final IPermissionStore permissionStore;
    private final IGroupStore groupStore;
    private final IUserStore userStore;
    private DBCollection extensionCollection;
    private ModifiableHistorizedResourceStore<ExtensionDefinition> extensionResourceStore;
    private IResourceFilter<ExtensionDefinition> resourceFilter;

    @Inject
    public ExtensionStore(DB database,
                          IPermissionStore permissionStore,
                          IGroupStore groupStore,
                          IUserStore userStore) {
        this.permissionStore = permissionStore;
        this.groupStore = groupStore;
        this.userStore = userStore;
        RuntimeUtilities.checkNotNull(database, "database");

        extensionCollection = database.getCollection(COLLECTION_EXTENSIONS);
        MongoResourceStorage<ExtensionDefinition> resourceStorage = new MongoResourceStorage<ExtensionDefinition>(database, COLLECTION_EXTENSIONS, new IDocumentBuilder<ExtensionDefinition>() {
            @Override
            public ExtensionDefinition build(String doc) throws IOException {
                return JSONSerialization.deserialize(doc, new TypeReference<ExtensionDefinition>() {});
            }
        });
        this.extensionResourceStore = new ModifiableHistorizedResourceStore<>(resourceStorage);
        this.resourceFilter = new ResourceFilter<>(extensionCollection, extensionResourceStore, permissionStore, userStore, groupStore, new IDocumentBuilder<ExtensionDefinition>() {
            @Override
            public ExtensionDefinition build(String doc) throws IOException {
                return JSONSerialization.deserialize(doc, new TypeReference<ExtensionDefinition>() {
                });
            }
        });
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
        IResourceFilter.QueryFilters queryFilters = new IResourceFilter.QueryFilters(Arrays.asList(queryFilter));
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
