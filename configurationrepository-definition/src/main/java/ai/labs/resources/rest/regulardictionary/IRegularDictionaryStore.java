package ai.labs.resources.rest.regulardictionary;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;

import java.util.List;

/**
 * @author ginccc
 */
public interface IRegularDictionaryStore extends IResourceStore<RegularDictionaryConfiguration> {
    RegularDictionaryConfiguration read(String id, Integer version, String filter, String order, Integer index, Integer limit) throws ResourceNotFoundException, ResourceStoreException;

    List<String> readExpressions(String id, Integer version, String filter, String order, Integer index, Integer limit) throws ResourceStoreException, ResourceNotFoundException;
}
