/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Refuses a write to an {@code in-memory} vector store that was replaced or
 * removed while the writer still held it: the knowledge base changed its
 * embedding configuration, moved to another store, or was deleted.
 * <p>
 * Nothing is written. The writer's run stops; the next run notices that the
 * store it recorded is gone (its epoch no longer matches) and re-embeds into
 * the knowledge base's current store.
 */
public class InMemoryStoreRetiredException extends IllegalStateException {

    public InMemoryStoreRetiredException(String knowledgeBaseId) {
        super("The in-memory vector store of knowledge base " + sanitize(knowledgeBaseId)
                + " was replaced or removed while this run was writing to it (its embedding configuration changed, "
                + "it moved to another store, or it was deleted). Nothing more was written to it; the next run "
                + "re-embeds into the knowledge base's current store.");
    }
}
