/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.snippets;

import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Store interface for {@link PromptSnippet} configuration documents.
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface IPromptSnippetStore extends IResourceStore<PromptSnippet> {

    /**
     * Read all non-deleted snippets (latest versions). Used by
     * {@link ai.labs.eddi.modules.llm.impl.PromptSnippetService} to populate the
     * template data map.
     */
    List<PromptSnippet> readAll() throws IResourceStore.ResourceStoreException;
}
