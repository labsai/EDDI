/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.IAuditStore;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * REST implementation for the audit ledger — delegates to {@link IAuditStore}.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestAuditStore implements IRestAuditStore {

    private final IAuditStore auditStore;

    @Inject
    public RestAuditStore(IAuditStore auditStore) {
        this.auditStore = auditStore;
    }

    @Override
    public List<AuditEntry> getAuditTrail(String conversationId, int skip, int limit) {
        return auditStore.getEntries(conversationId, skip, limit);
    }

    @Override
    public List<AuditEntry> getAuditTrailByAgent(String agentId, Integer agentVersion, int skip, int limit) {
        return auditStore.getEntriesByAgent(agentId, agentVersion, skip, limit);
    }

    @Override
    public long getEntryCount(String conversationId) {
        return auditStore.countByConversation(conversationId);
    }
}
