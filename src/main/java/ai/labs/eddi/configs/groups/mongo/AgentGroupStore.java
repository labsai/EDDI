/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * DB-agnostic store for group configurations. Extends
 * {@link AbstractResourceStore} which delegates to either MongoDB or PostgreSQL
 * via {@link IResourceStorageFactory}.
 *
 * @author ginccc
 */
@ApplicationScoped
public class AgentGroupStore extends AbstractResourceStore<AgentGroupConfiguration> implements IAgentGroupStore {

    private static final Logger LOGGER = Logger.getLogger(AgentGroupStore.class);

    @Inject
    public AgentGroupStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "groups", documentBuilder, AgentGroupConfiguration.class);
    }

    @Override
    public IResourceStore.IResourceId create(AgentGroupConfiguration groupConfiguration)
            throws IResourceStore.ResourceStoreException {
        HitlConfigValidation.validate(groupConfiguration.getHitlConfig());
        normalizeNonPositiveCostCeiling(groupConfiguration);
        return super.create(groupConfiguration);
    }

    @Override
    @IResourceStore.ConfigurationUpdate
    public Integer update(String id, Integer version, AgentGroupConfiguration groupConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException,
            IResourceStore.ResourceNotFoundException {
        HitlConfigValidation.validate(groupConfiguration.getHitlConfig());
        normalizeNonPositiveCostCeiling(groupConfiguration);
        return super.update(id, version, groupConfiguration);
    }

    /**
     * I1: a {@code maxCostPerDiscussion} of zero or less would stop the very first
     * turn of every discussion this group ever runs — almost certainly a mistake (a
     * placeholder, or a unit mix-up) rather than a deliberate "never run me".
     * Coalesced to {@code null} (unlimited) with a warning rather than rejected, so
     * an existing config with a bad value keeps loading and saving instead of
     * becoming unfixable through the same API that stored it. Same warn-and-mutate
     * shape as {@code RagStore.normalizeLegacyChunkStrategy}.
     */
    private void normalizeNonPositiveCostCeiling(AgentGroupConfiguration groupConfiguration) {
        var protocol = groupConfiguration.getProtocol();
        if (protocol == null || protocol.maxCostPerDiscussion() == null || protocol.maxCostPerDiscussion() > 0) {
            return;
        }
        LOGGER.warnf("Group '%s' has maxCostPerDiscussion=%s (not positive) — treating as unlimited",
                groupConfiguration.getName(), protocol.maxCostPerDiscussion());
        groupConfiguration.setProtocol(new AgentGroupConfiguration.ProtocolConfig(
                protocol.agentTimeoutSeconds(), protocol.onAgentFailure(), protocol.maxRetries(),
                protocol.onMemberUnavailable(), protocol.maxTurns(), null, protocol.onCostExceeded()));
    }
}
