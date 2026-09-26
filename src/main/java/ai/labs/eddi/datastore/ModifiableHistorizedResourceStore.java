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

    /**
     * Rewrites version {@code version} of a resource in place, without creating a
     * new version.
     * <p>
     * Two defects used to hide here. Writing the <em>current</em> version replaced
     * the row by id alone, so an update that committed between the read and the
     * write was overwritten with the older content — the resource rolled back to
     * {@code version} while the caller's view said nothing had happened. It is now
     * version-checked, and a version that turns out to have moved into history is
     * written there instead. Writing a <em>historized</em> version went through the
     * archive's insert-if-absent path and was therefore a silent no-op that still
     * answered success; it now rewrites the history row.
     *
     * @throws IResourceStore.ResourceNotFoundException
     *             if the version does not exist, or the resource was deleted
     */
    public Integer set(String id, Integer version, T content) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkNotNull(id, "id");
        RuntimeUtilities.checkNotNull(version, "version");
        RuntimeUtilities.checkNotNull(content, "content");

        try {
            IResourceStorage.IResource<T> updatedResource = resourceStorage.newResource(id, version, content);
            if (resourceStorage.read(id, version) != null) {
                try {
                    resourceStorage.storeIfCurrentVersion(updatedResource, version);
                    return version;
                } catch (IResourceStore.ResourceModifiedException e) {
                    // Moved on since the read: the version is history now — write it there,
                    // never over the newer current row.
                }
            }

            IResourceStorage.IHistoryResource<T> historyLatest = resourceStorage.readHistoryLatest(id);
            if (historyLatest == null || historyLatest.isDeleted() || version > historyLatest.getVersion()) {
                throw createResourceNotFoundException(id, version);
            }
            IResourceStorage.IHistoryResource<T> historized = resourceStorage.readHistory(id, version);
            if (historized == null || historized.isDeleted()) {
                throw createResourceNotFoundException(id, version);
            }
            if (!resourceStorage.replaceHistory(resourceStorage.newHistoryResourceFor(updatedResource, false))) {
                throw createResourceNotFoundException(id, version);
            }
            return version;
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
