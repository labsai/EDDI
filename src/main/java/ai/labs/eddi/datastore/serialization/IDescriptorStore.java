/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IDescriptorStore<T> {

    /**
     * Explicit "give me everything" sentinel for the {@code limit} argument of
     * {@link #readDescriptors}. Prefer this over a bare {@code 0} or an arbitrarily
     * large magic number so the intent is readable at the call site.
     */
    int NO_LIMIT = 0;

    /**
     * Page size used when {@code limit} is {@code null}, i.e. when the caller
     * expressed no opinion at all. Matches the {@code @DefaultValue("20")} the REST
     * layer declares on its {@code limit} query parameter.
     */
    int DEFAULT_LIMIT = 20;

    /**
     * Read descriptors of a given type, optionally filtered and paginated.
     * <p>
     * Limit semantics — a {@code 0} here means <em>unlimited</em>, not "use the
     * default page size":
     * <ul>
     * <li>{@code null} — caller expressed no opinion; {@link #DEFAULT_LIMIT}
     * descriptors are returned.</li>
     * <li>{@code <= 0} ({@link #NO_LIMIT}) — no caller-imposed limit; returns up to
     * {@link ai.labs.eddi.datastore.IResourceStorage#MAX_RESULT_LIMIT}.</li>
     * <li>{@code > 0} — at most that many, clamped to
     * {@link ai.labs.eddi.datastore.IResourceStorage#MAX_RESULT_LIMIT}.</li>
     * </ul>
     * Implementations must log a warning when a result set is truncated by the
     * ceiling, so a silently short list can never be mistaken for a complete one.
     *
     * @param index
     *            zero-based page index; the skip offset is
     *            {@code index * effectiveLimit}, so any {@code index > 0} combined
     *            with {@link #NO_LIMIT} is past the (single) unlimited page and
     *            yields an empty list
     * @param limit
     *            see above
     */
    List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    /**
     * Resolve the {@code limit} argument of {@link #readDescriptors} into a
     * concrete positive row count, per the semantics documented there.
     *
     * @param limit
     *            the caller-supplied limit, may be {@code null}
     * @return a positive row count, never above
     *         {@link ai.labs.eddi.datastore.IResourceStorage#MAX_RESULT_LIMIT}
     */
    static int resolveDescriptorLimit(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : IResourceStorage.resolveLimit(limit);
    }

    T readDescriptor(String resourceId, Integer version) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    T readDescriptorWithHistory(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    Integer updateDescriptor(String resourceId, Integer version, T descriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException;

    void setDescriptor(String resourceId, Integer version, T descriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    void createDescriptor(String resourceId, Integer version, T descriptor) throws IResourceStore.ResourceStoreException;

    IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException;

    void deleteDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException;

    void deleteAllDescriptor(String resourceId);

    /**
     * Find descriptors by their origin ID (the resource ID from the exporting
     * instance). Used during merge import to find existing resources that were
     * previously imported.
     *
     * @param originId
     *            the resource ID from the source instance
     * @return list of matching descriptors, empty if none found
     */
    List<T> findByOriginId(String originId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
