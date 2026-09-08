/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Persistence store for RAG knowledge-base configurations (embedding provider,
 * vector store, and default retrieval). {@code ResourceClientLibrary} and the
 * LLM task load these at execution time; the REST RAG API exposes CRUD and
 * ingestion.
 */
public interface IRagStore extends IResourceStore<RagConfiguration> {
}
