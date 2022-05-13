package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.RuntimeUtilities;

import java.io.IOException;

/**
 * @author ginccc
 */
public class ModifiableHistorizedResourceStore<T> extends HistorizedResourceStore<T> implements IResourceStore<T> {
    public ModifiableHistorizedResourceStore(IResourceStorage<T> resourceStore) {
        super(resourceStore);
        this.resourceStorage = resourceStore;
    }

    public Integer set(String id, Integer version, T content) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(content, "content");

        IResourceStorage.IResource<T> resource = resourceStorage.read(id, version);
        try {
            if (resource == null) {
                IResourceStorage.IHistoryResource<T> historyLatest = resourceStorage.readHistoryLatest(id);

                if (historyLatest == null || historyLatest.isDeleted() || version > historyLatest.getVersion()) {
                    throw createResourceNotFoundException(id, version);
                }

                //it's a update request for a historized resource, so we update the history resource
                IResourceStorage.IResource<T> updatedResource = resourceStorage.newResource(id, version, content);
                IResourceStorage.IHistoryResource<T> updatedHistorizedResource = resourceStorage.newHistoryResourceFor(updatedResource, false);
                resourceStorage.store(updatedHistorizedResource);
                return version;
            } else {
                //it's a update request for the current resource, so we update the current resource
                IResourceStorage.IResource<T> updatedResource = resourceStorage.newResource(id, version, content);
                resourceStorage.store(updatedResource);
                return version;
            }
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    public IResourceStore.IResourceId create(final String id, final Integer version, T content) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(content, "content");

        try {
            IResourceStorage.IResource<T> currentResource = resourceStorage.newResource(id, version, content);
            resourceStorage.store(currentResource);
            return currentResource;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    public IResourceStore.IResourceId createNew(final String id, final Integer version, T content) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(content, "content");

        try {
            IResourceStorage.IResource<T> currentResource = resourceStorage.newResource(id, version, content);
            resourceStorage.createNew(currentResource);
            return currentResource;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }


}
