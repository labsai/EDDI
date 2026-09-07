/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.snippets;

import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Persistence store for reusable prompt-snippet documents.
 * {@code PromptSnippetService} reads them into the LLM template data map so
 * system prompts can pull in {@code {snippets.name}} fragments at task
 * execution time.
 *
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
