/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Store interface for RAG (Knowledge Base) configurations.
 */
public interface IRagStore extends IResourceStore<RagConfiguration> {
}
