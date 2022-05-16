package ai.labs.eddi.eml;

import ai.labs.eddi.datastore.IResourceStore;

public interface IEMLProducer {
    String produceMarkup(String packageId, Integer packageVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
