package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;

import java.io.IOException;

import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;

/**
 * @author ginccc
 */
public class HistorizedResourceStore<T> implements IResourceStore<T> {

    static ResourceNotFoundException createResourceNotFoundException(String id, Integer version) {
        String message = "Resource not found. (id=%s, version=%s)";
        message = String.format(message, id, version);
        return new ResourceNotFoundException(message);
    }

    private static ResourceModifiedException createResourceAlreadyModifiedException(String id, Integer version) {
        String message = "Resource already modified. Local update is necessary. (id=%s, version=%s)";
        message = String.format(message, id, version);
        return new ResourceModifiedException(message);
    }

    IResourceStorage<T> resourceStorage;

    public HistorizedResourceStore(IResourceStorage<T> resourceStore) {
        this.resourceStorage = resourceStore;
    }

    @Override
    public Integer update(String id, Integer version, T content)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {

        checkNotNull(id, "id");
        checkNotNull(version, "version");
        checkNotNull(content, "content");

        var resource = resourceStorage.read(id, version);

        checkIfFoundAndLatest(id, version, resource);

        var history = resourceStorage.newHistoryResourceFor(resource, false);
        resourceStorage.store(history);

        try {
            Integer newVersion = resource.getVersion() + 1;
            var newResource =
                    resourceStorage.newResource(resource.getId(), newVersion, content);
            resourceStorage.store(newResource);
            return newVersion;
        } catch (IOException e) {
            throw new ResourceStoreException(e.getLocalizedMessage(), e);
        }

    }

    @Override
    public IResourceStore.IResourceId create(T content) throws ResourceStoreException {
        checkNotNull(content, "content");

        try {
            var currentResource = resourceStorage.newResource(content);
            resourceStorage.store(currentResource);
            return currentResource;
        } catch (IOException e) {
            throw new ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public synchronized void delete(String id, Integer version)
            throws ResourceModifiedException, ResourceNotFoundException {

        checkNotNull(id, "id");
        checkNotNull(version, "version");

        var resource = resourceStorage.read(id, version);

        checkIfFoundAndLatest(id, version, resource);

        var historyResource =
                resourceStorage.newHistoryResourceFor(resource, true);
        resourceStorage.store(historyResource);

        resourceStorage.remove(id);
    }

    private void checkIfFoundAndLatest(String id, Integer version, IResourceStorage.IResource<?> resource)
            throws ResourceNotFoundException, ResourceModifiedException {

        if (resource == null) {
            var historyLatest = resourceStorage.readHistoryLatest(id);

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
    public IResourceStore.IResourceId getCurrentResourceId(final String id)
            throws ResourceNotFoundException {

        checkNotNull(id, "id");

        final Integer version = resourceStorage.getCurrentVersion(id);

        if (version == -1) {
            throw new ResourceNotFoundException("No document found for id (" + id + ")");
        }

        return new IResourceStore.IResourceId() {
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
    public T read(String id, Integer version)
            throws ResourceNotFoundException, ResourceStoreException {

        checkNotNull(id, "id");
        checkNotNull(version, "version");

        IResourceStorage.IResource<T> current = resourceStorage.read(id, version);

        if (current == null) {
            var historyResource = resourceStorage.readHistory(id, version);

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
            throw new ResourceStoreException(message, e);
        }
    }

    @Override
    public T readIncludingDeleted(String id, Integer version)
            throws ResourceNotFoundException, ResourceStoreException {

        checkNotNull(id, "id");
        checkNotNull(version, "version");

        IResourceStorage.IResource<T> current = resourceStorage.read(id, version);

        if (current == null) {
            var historyResource = resourceStorage.readHistory(id, version);

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
            throw new ResourceStoreException(message, e);
        }
    }
}
