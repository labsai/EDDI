package io.sls.persistence.impl.mongo;

import io.sls.persistence.IResourceStorage;
import io.sls.persistence.IResourceStore;
import io.sls.utilities.RuntimeUtilities;

import java.io.IOException;

/**
 * Created with IntelliJ IDEA.
 * User: Michael
 * Date: 14.08.12
 * Time: 14:44
 * To change this template use File | Settings | File Templates.
 */
public class HistorizedResourceStore<T> implements IResourceStore<T> {
    protected static IResourceStore.ResourceNotFoundException createResourceNotFoundException(String id, Integer version) {
        String message = "Resource not found. (id=%s, version=%s)";
        message = String.format(message, id, version);
        return new IResourceStore.ResourceNotFoundException(message);
    }

    private static ResourceModifiedException createResourceAlreadyModifiedException(String id, Integer version) {
        String message = "Resource already modified. Local update is necessary. (id=%s, version=%s)";
        message = String.format(message, id, version);
        return new IResourceStore.ResourceModifiedException(message);
    }

    public static final String DELETED_FIELD = "_deleted";
    public static final String ID_FIELD = "_id";
    public static final String VERSION_FIELD = "_version";

    private static final String HISTORY_POSTFIX = ".history";

    protected IResourceStorage<T> resourceStorage;

    public HistorizedResourceStore(IResourceStorage<T> resourceStore) {
        this.resourceStorage = resourceStore;
    }

    @Override
    public Integer update(String id, Integer version, T content) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(content, "content");

        IResourceStorage.IResource resource = resourceStorage.read(id, version);

        if (resource == null) {
            IResourceStorage.IHistoryResource historyLatest = resourceStorage.readHistoryLatest(id);

            if (historyLatest == null || historyLatest.isDeleted() || version > historyLatest.getVersion()) {
                throw createResourceNotFoundException(id, version);
            }

            throw createResourceAlreadyModifiedException(id, version);
        }

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
    public synchronized void delete(String id, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");

        IResourceStorage.IResource resource = resourceStorage.read(id, version);

        if (resource == null) {
            IResourceStorage.IHistoryResource historyLatest = resourceStorage.readHistoryLatest(id);

            if (historyLatest == null || historyLatest.isDeleted() || version > historyLatest.getVersion()) {
                throw createResourceNotFoundException(id, version);
            }

            throw createResourceAlreadyModifiedException(id, version);
        }

        IResourceStorage.IHistoryResource historyResource = resourceStorage.newHistoryResourceFor(resource, true);
        resourceStorage.store(historyResource);

        resourceStorage.remove(id);
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


}
