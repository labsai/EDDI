/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public interface IResourceStorage<T> {

    /**
     * Hard safety ceiling for any single {@link #findResources} query. Applies even
     * when the caller asks for "no limit" — an unbounded query against a large
     * collection is a memory risk, so the storage layer never returns more than
     * this many ids in one call.
     * <p>
     * Callers that must be exhaustive on collections this large have to page (see
     * {@code RestOrphanAdmin} for the pattern).
     */
    int MAX_RESULT_LIMIT = 10_000;

    /**
     * Resolve a caller-supplied limit into the concrete number of rows a backend
     * should fetch.
     * <p>
     * {@code limit <= 0} is the explicit "no caller-imposed limit" sentinel and
     * resolves to {@link #MAX_RESULT_LIMIT}; a positive limit is honoured but still
     * clamped to the ceiling. Shared by every backend so the two implementations
     * cannot drift apart.
     *
     * @param limit
     *            the caller-supplied limit ({@code <= 0} means unlimited)
     * @return a positive row count, never above {@link #MAX_RESULT_LIMIT}
     */
    static int resolveLimit(int limit) {
        return limit < 1 ? MAX_RESULT_LIMIT : Math.min(limit, MAX_RESULT_LIMIT);
    }

    IResource<T> newResource(T content) throws IOException;

    IResource<T> newResource(String id, Integer version, T content) throws IOException;

    void store(IResource<T> resource);

    void createNew(IResource<T> resource);

    IResource<T> read(String id, Integer version);

    /**
     * Batch counterpart of {@link #read(String, Integer)}.
     * <p>
     * Exists so listing code does not have to issue one round trip per id. The
     * default implementation is the naive N+1 loop and is only there so backends
     * that cannot batch keep working; every backend that can express an
     * {@code IN (...)} should override it.
     * <p>
     * Contract: the returned list is in the <b>same order</b> as {@code ids}, and
     * entries that no longer exist (or whose version no longer matches) are
     * silently skipped — so the result may be shorter than the input.
     *
     * @param ids
     *            the resource ids (with versions) to read
     * @return the resources, in request order, missing ones omitted
     */
    default List<IResource<T>> readMany(List<IResourceStore.IResourceId> ids) {
        List<IResource<T>> resources = new ArrayList<>(ids.size());
        for (IResourceStore.IResourceId id : ids) {
            IResource<T> resource = read(id.getId(), id.getVersion());
            if (resource != null) {
                resources.add(resource);
            }
        }
        return resources;
    }

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
     * Archive {@code history} and apply the version-checked update as ONE unit of
     * work.
     * <p>
     * {@link ai.labs.eddi.datastore.HistorizedResourceStore#update} previously
     * issued the two writes back to back: a crash in between left the old version
     * archived twice-over or the new version stored without its predecessor
     * archived. Backends that have transactions must override this and run both
     * statements inside one.
     * <p>
     * The default is the historical sequential behaviour — history FIRST, so a
     * failure of the second write can never lose the archived predecessor.
     *
     * @param history
     *            the archived predecessor
     * @param newResource
     *            the new version to store
     * @param expectedCurrentVersion
     *            the version the caller believes is currently stored
     * @throws IResourceStore.ResourceModifiedException
     *             if the current version no longer matches (concurrent edit)
     */
    default void storeHistoryAndUpdate(IHistoryResource<T> history, IResource<T> newResource, int expectedCurrentVersion)
            throws IResourceStore.ResourceModifiedException {
        store(history);
        storeIfCurrentVersion(newResource, expectedCurrentVersion);
    }

    /**
     * Archive {@code history} (flagged deleted) and remove the current row as ONE
     * unit of work.
     * <p>
     * Non-atomically, a crash between the two writes leaves an archived-as-deleted
     * row while the live row is still present — the resource looks deleted in
     * history and alive in the current collection at the same time. Backends that
     * have transactions must override this.
     *
     * @param history
     *            the archived, deleted-flagged version
     * @param id
     *            the resource id to remove from the current collection
     */
    default void storeHistoryAndRemove(IHistoryResource<T> history, String id) {
        store(history);
        remove(id);
    }

    /**
     * Find resource IDs where the JSON data contains the given value at the given
     * path. Used by AgentStore/WorkflowStore for "find configs containing resource"
     * queries.
     * <p>
     * {@code jsonPath} is a dot-separated path and every backend MUST traverse it,
     * descending into arrays along the way: {@code workflowSteps.config.uri}
     * matches a document whose {@code workflowSteps} array holds an element with
     * {@code config.uri == value}. A backend that treats the dotted string as a
     * single literal key silently returns nothing forever, which is worse than
     * failing.
     *
     * @param jsonPath
     *            the JSON field/array path (e.g. "workflows",
     *            "workflowSteps.config.uri")
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
     *            maximum number of results; {@code <= 0} means "no caller-imposed
     *            limit" and returns up to {@link #MAX_RESULT_LIMIT}.
     *            Implementations must resolve this through
     *            {@link #resolveLimit(int)}.
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
