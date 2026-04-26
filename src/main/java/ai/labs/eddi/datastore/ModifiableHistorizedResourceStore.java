/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.utils.RuntimeUtilities;

import java.io.IOException;

/**
 * Extended version of {@link HistorizedResourceStore} that supports in-place
 * modification (set) and explicit ID creation.
 * <p>
 * Used by descriptor stores and other stores that need to update specific
 * versions without creating a new version.
 *
 * @param <T>
 *            the resource document type
 */
public class ModifiableHistorizedResourceStore<T> extends HistorizedResourceStore<T> {
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

                // it's a update request for a historized resource, so we update the history
                // resource
                IResourceStorage.IResource<T> updatedResource = resourceStorage.newResource(id, version, content);
                IResourceStorage.IHistoryResource<T> updatedHistorizedResource = resourceStorage.newHistoryResourceFor(updatedResource, false);
                resourceStorage.store(updatedHistorizedResource);
                return version;
            } else {
                // it's a update request for the current resource, so we update the current
                // resource
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
