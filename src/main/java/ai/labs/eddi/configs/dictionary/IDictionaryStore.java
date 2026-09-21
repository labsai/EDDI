/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.dictionary;

import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Store for dictionary configurations, the word, regex and phrase lists an
 * agent's parser and rules expand while matching input. readExpressions()
 * returns the expressions of a given version and is what the expression listing
 * endpoints call to fill their results.
 */
public interface IDictionaryStore extends IResourceStore<DictionaryConfiguration> {
    DictionaryConfiguration read(String id, Integer version, String filter, String order, Integer index, Integer limit)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    List<String> readExpressions(String id, Integer version, String filter, String order, Integer index, Integer limit)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
