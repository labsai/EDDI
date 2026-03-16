package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorage;

/**
 * Backward-compatible wrapper - delegates to the relocated
 * {@link ai.labs.eddi.datastore.HistorizedResourceStore}.
 *
 * @deprecated Use {@link ai.labs.eddi.datastore.HistorizedResourceStore} instead
 */
@Deprecated
public class HistorizedResourceStore<T> extends ai.labs.eddi.datastore.HistorizedResourceStore<T> {
    @Deprecated
    public HistorizedResourceStore(IResourceStorage<T> resourceStore) {
        super(resourceStore);
    }
}
