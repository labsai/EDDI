package ai.labs.eddi.configs.regulardictionary;

import ai.labs.eddi.configs.regulardictionary.model.RegularDictionaryConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * @author ginccc
 */
public interface IRegularDictionaryStore extends IResourceStore<RegularDictionaryConfiguration> {
    RegularDictionaryConfiguration read(String id, Integer version, String filter, String order, Integer index, Integer limit) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    List<String> readExpressions(String id, Integer version, String filter, String order, Integer index, Integer limit) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
