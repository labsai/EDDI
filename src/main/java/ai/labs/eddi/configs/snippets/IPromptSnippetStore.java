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
 * system prompts can pull in {@code {snippets.<name>}} fragments at task
 * execution time.
 *
 * @since 6.0.0
 */
public interface IPromptSnippetStore extends IResourceStore<PromptSnippet> {

    /**
     * Not supported by the shipped implementation, which throws
     * {@code ResourceStoreException}. Enumerate snippets through
     * {@link ai.labs.eddi.modules.llm.impl.PromptSnippetService}.
     */
    List<PromptSnippet> readAll() throws IResourceStore.ResourceStoreException;
}
