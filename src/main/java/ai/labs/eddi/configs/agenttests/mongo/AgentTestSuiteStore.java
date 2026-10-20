/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agenttests.mongo;

import ai.labs.eddi.configs.agenttests.AgentTestSuiteValidator;
import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Store for {@link AgentTestSuite} configuration documents. Backend-agnostic:
 * {@link IResourceStorageFactory} resolves to MongoDB or PostgreSQL, like every
 * {@code AbstractResourceStore}.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class AgentTestSuiteStore extends AbstractResourceStore<AgentTestSuite> implements IAgentTestSuiteStore {

    public static final String COLLECTION_NAME = "agenttestsuites";

    @Inject
    public AgentTestSuiteStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, COLLECTION_NAME, documentBuilder, AgentTestSuite.class);
    }

    @Override
    protected void validate(AgentTestSuite content) {
        AgentTestSuiteValidator.validate(content);
    }
}
