/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;

/**
 * Store for LLM configurations, i.e. the provider and model settings behind an
 * agent's language tasks. Versioned like every other configuration resource and
 * mostly read through the generic configuration client when an agent is
 * deployed or edited.
 */
public interface ILlmStore extends IResourceStore<LlmConfiguration> {
}
