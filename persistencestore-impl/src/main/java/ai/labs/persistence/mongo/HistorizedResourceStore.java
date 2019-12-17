package ai.labs.persistence.mongo;

import ai.labs.persistence.IResourceStorage;
import ai.labs.persistence.IResourceStore;
import ai.labs.utilities.RuntimeUtilities;

import java.io.IOException;

/**
 * @author ginccc
 */
public class HistorizedResourceStore<T> implements IResourceStore<T> {

    static IResourceStore.ResourceNotFoundException createResourceNotFoundException(String id, Integer version) {
        String message = "Resource not found. (id=%s, version=%s)";
        message = String.format(message, id, version);
        return new IResourceStore.ResourceNotFoundException(message);
    }

    private static ResourceModifiedException createResourceAlreadyModifiedException(String id, Integer version) {
        String message = "Resource already modified. Local update is necessary. (id=%s, version=%s)";
        message = String.format(message, id, version);
        return new IResourceStore.ResourceModifiedException(message);
    }

    IResourceStorage<T> resourceStorage;

    public HistorizedResourceStore(IResourceStorage<T> resourceStore) {
        this.resourceStorage = resourceStore;
    }

    @Override
    public Integer update(String id, Integer version, T content) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(content, "content");

        IResourceStorage.IResource resource = resourceStorage.read(id, version);

        checkIfFoundAndLatest(id, version, resource);

        IResourceStorage.IHistoryResource history = resourceStorage.newHistoryResourceFor(resource, false);
        resourceStorage.store(history);

        try {
            Integer newVersion = resource.getVersion() + 1;
            IResourceStorage.IResource newResource = resourceStorage.newResource(resource.getId(), newVersion, content);
            resourceStorage.store(newResource);
            return newVersion;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }

    }

    @Override
    public IResourceId create(T content) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(content, "content");

        try {
            IResourceStorage.IResource currentResource = resourceStorage.newResource(content);
            resourceStorage.store(currentResource);
            return currentResource;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public synchronized void delete(String id, Integer version) throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");

        IResourceStorage.IResource resource = resourceStorage.read(id, version);

        checkIfFoundAndLatest(id, version, resource);

        IResourceStorage.IHistoryResource historyResource = resourceStorage.newHistoryResourceFor(resource, true);
        resourceStorage.store(historyResource);

        resourceStorage.remove(id);
    }

    private void checkIfFoundAndLatest(String id, Integer version, IResourceStorage.IResource resource) throws ResourceNotFoundException, ResourceModifiedException {
        if (resource == null) {
            IResourceStorage.IHistoryResource historyLatest = resourceStorage.readHistoryLatest(id);

            if (historyLatest == null || historyLatest.isDeleted() || version > historyLatest.getVersion()) {
                throw createResourceNotFoundException(id, version);
            }

            throw createResourceAlreadyModifiedException(id, version);
        }
    }

    @Override
    public synchronized void deleteAllPermanently(String id) {
        resourceStorage.removeAllPermanently(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(final String id) throws ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(id, "id");

        final Integer version = resourceStorage.getCurrentVersion(id);

        if (version == -1) {
            throw new ResourceNotFoundException("No document found for id (" + id + ")");
        }

        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    @Override
    public T read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");

        IResourceStorage.IResource<T> current = resourceStorage.read(id, version);

        if (current == null) {
            IResourceStorage.IHistoryResource historyResource = resourceStorage.readHistory(id, version);

            if (historyResource == null || historyResource.isDeleted()) {
                throw createResourceNotFoundException(id, version);
            }

            current = historyResource;
        }

        try {
            return current.getData();
        } catch (IOException e) {
            String message = "Unable to deserialize resource (id=%s, version=%s)";
            message = String.format(message, id, version);
            throw new IResourceStore.ResourceStoreException(message, e);
        }
    }

    @Override
    public T readIncludingDeleted(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");

        IResourceStorage.IResource<T> current = resourceStorage.read(id, version);

        if (current == null) {
            IResourceStorage.IHistoryResource historyResource = resourceStorage.readHistory(id, version);

            if (historyResource == null) {
                throw createResourceNotFoundException(id, version);
            }

            current = historyResource;
        }

        try {
            return current.getData();
        } catch (IOException e) {
            String message = "Unable to deserialize resource (id=%s, version=%s)";
            message = String.format(message, id, version);
            throw new IResourceStore.ResourceStoreException(message, e);
        }
    }


}
