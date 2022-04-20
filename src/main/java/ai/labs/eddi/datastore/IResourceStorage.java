package ai.labs.eddi.datastore;


import java.io.IOException;

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

    IHistoryResource<T> newHistoryResourceFor(IResource resource, boolean deleted);

    void store(IHistoryResource<T> history);

    Integer getCurrentVersion(String id);

    interface IResource<T> extends IResourceStore.IResourceId {

        T getData() throws IOException;

    }

    interface IHistoryResource<T> extends IResource<T> {

        boolean isDeleted();

    }
}
