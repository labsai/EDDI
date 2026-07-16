/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public interface IResourceStorage<T> {
    IResource<T> newResource(T content) throws IOException;

    IResource<T> newResource(String id, Integer version, T content) throws IOException;

    void store(IResource<T> resource);

    void createNew(IResource<T> resource);

    IResource<T> read(String id, Integer version);

    void remove(String id);

    void removeAllPermanently(String id);

    IHistoryResource<T> readHistory(String id, Integer version);

    IHistoryResource<T> readHistoryLatest(String id);

    IHistoryResource<T> newHistoryResourceFor(IResource<T> resource, boolean deleted);

    void store(IHistoryResource<T> history);

    Integer getCurrentVersion(String id);

    /**
     * Store a new version of a resource only if the current version in the database
     * matches {@code expectedCurrentVersion}.
     * <p>
     * Implementations that support conditional writes should override this method
     * to enforce the check atomically. The default implementation delegates to
     * {@link #store(IResource)} and does <strong>not</strong> provide optimistic
     * locking.
     * <p>
     * Used by {@link ai.labs.eddi.datastore.HistorizedResourceStore#update} for
     * optimistic locking.
     *
     * @param newResource
     *            the resource with the new version to store
     * @param expectedCurrentVersion
     *            the version the caller believes is currently stored
     * @throws IResourceStore.ResourceModifiedException
     *             if the current version no longer matches (concurrent edit)
     */
    default void storeIfCurrentVersion(IResource<T> newResource, int expectedCurrentVersion)
            throws IResourceStore.ResourceModifiedException {
        store(newResource);
    }

    /**
     * Store a new version of a resource only if the JSON field {@code fieldName}
     * currently equals {@code expectedValue}. Atomic compare-and-swap on an
     * arbitrary indexed field (not _version).
     * <p>
     * Zero-match outcomes are distinguished so callers can report honestly:
     * {@link IResourceStore.ResourceNotFoundException} when the resource no longer
     * exists (REST: 404), {@link IResourceStore.ResourceModifiedException} when it
     * exists but the field value did not match (a genuine CAS conflict — REST:
     * 409).
     * <p>
     * There is deliberately NO fallback default: silently degrading a CAS to an
     * unconditional store would defeat every race-hardening built on it. New
     * backends must implement this.
     */
    default void storeIfFieldEquals(IResource<T> newResource, String fieldName, String expectedValue)
            throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        throw new UnsupportedOperationException(
                "storeIfFieldEquals is not implemented by " + getClass().getName()
                        + " — a compare-and-swap must never silently degrade to an unconditional store");
    }

    /**
     * Find resource IDs where the JSON data contains the given value at the given
     * path. Used by AgentStore/WorkflowStore for "find configs containing resource"
     * queries.
     *
     * @param jsonPath
     *            the JSON field/array path (e.g. "packages",
     *            "WorkflowSteps.config.uri")
     * @param value
     *            the value to search for within the field
     * @return list of matching resource IDs with their current versions
     */
    default List<IResourceStore.IResourceId> findResourceIdsContaining(String jsonPath, String value) {
        return Collections.emptyList();
    }

    /**
     * Find resource IDs where the JSON data contains the given value at the given
     * path, searching in the history collection as well.
     *
     * @param jsonPath
     *            the JSON field/array path
     * @param value
     *            the value to search for
     * @return list of matching resource IDs with versions from history
     */
    default List<IResourceStore.IResourceId> findHistoryResourceIdsContaining(String jsonPath, String value) {
        return Collections.emptyList();
    }

    /**
     * Filter and paginate resources by field values, with optional regex matching.
     * Used by DescriptorStore for listing/searching descriptors.
     *
     * @param filters
     *            the filter criteria (field/value pairs, strings are treated as
     *            regex)
     * @param sortField
     *            field to sort by (descending)
     * @param skip
     *            number of results to skip
     * @param limit
     *            maximum number of results
     * @return list of matching resource IDs
     */
    default List<IResourceStore.IResourceId> findResources(IResourceFilter.QueryFilters[] filters, String sortField, int skip, int limit) {
        return Collections.emptyList();
    }

    interface IResource<T> extends IResourceStore.IResourceId {

        T getData() throws IOException;

    }

    interface IHistoryResource<T> extends IResource<T> {

        boolean isDeleted();

    }
}
