package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorage;

/**
 * Backward-compatible wrapper - delegates to the relocated
 * {@link ai.labs.eddi.datastore.ModifiableHistorizedResourceStore}.
 *
 * @deprecated Use {@link ai.labs.eddi.datastore.ModifiableHistorizedResourceStore} instead
 */
@Deprecated
public class ModifiableHistorizedResourceStore<T> extends ai.labs.eddi.datastore.ModifiableHistorizedResourceStore<T> {
    public ModifiableHistorizedResourceStore(IResourceStorage<T> resourceStore) {
        super(resourceStore);
    }
}
