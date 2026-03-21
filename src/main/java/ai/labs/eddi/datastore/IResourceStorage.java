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
     * Find resource IDs where the JSON data contains the given value at the given path.
     * Used by AgentStore/PipelineStore for "find configs containing resource" queries.
     *
     * @param jsonPath the JSON field/array path (e.g. "packages", "PipelineSteps.config.uri")
     * @param value    the value to search for within the field
     * @return list of matching resource IDs with their current versions
     */
    default List<IResourceStore.IResourceId> findResourceIdsContaining(String jsonPath, String value) {
        return Collections.emptyList();
    }

    /**
     * Find resource IDs where the JSON data contains the given value at the given path,
     * searching in the history collection as well.
     *
     * @param jsonPath the JSON field/array path
     * @param value    the value to search for
     * @return list of matching resource IDs with versions from history
     */
    default List<IResourceStore.IResourceId> findHistoryResourceIdsContaining(String jsonPath, String value) {
        return Collections.emptyList();
    }

    /**
     * Filter and paginate resources by field values, with optional regex matching.
     * Used by DescriptorStore for listing/searching descriptors.
     *
     * @param filters   the filter criteria (field/value pairs, strings are treated as regex)
     * @param sortField field to sort by (descending)
     * @param skip      number of results to skip
     * @param limit     maximum number of results
     * @return list of matching resource IDs
     */
    default List<IResourceStore.IResourceId> findResources(
            IResourceFilter.QueryFilters[] filters, String sortField, int skip, int limit) {
        return Collections.emptyList();
    }

    interface IResource<T> extends IResourceStore.IResourceId {

        T getData() throws IOException;

    }

    interface IHistoryResource<T> extends IResource<T> {

        boolean isDeleted();

    }
}

