/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Audit-ledger records of Agent Health's administrative actions: a health
 * profile saved or deleted, an owner report link created or revoked. The
 * generic {@code AdminActionAuditFilter} leaves everything under
 * {@code /agents} out (it is the conversation API's root), so these are written
 * here, the way {@code TeamBudgetAudit} writes its own.
 * <p>
 * A record holds the actor, the agent and the link's id, sections and expiry —
 * <b>never a token</b> (nor its hash) and never the profile's body. Recording
 * never changes the outcome of the action: a ledger that fails is logged.
 */
@ApplicationScoped
public class HealthAdminAudit {

    private static final Logger LOGGER = Logger.getLogger(HealthAdminAudit.class);

    static final String TASK_ID = "ai.labs.health";
    static final String TASK_TYPE = "health";
    public static final String PROFILE_SAVED = "HEALTH_PROFILE_SAVED";
    public static final String PROFILE_DELETED = "HEALTH_PROFILE_DELETED";
    public static final String LINK_CREATED = "HEALTH_REPORT_LINK_CREATED";
    public static final String LINK_REVOKED = "HEALTH_REPORT_LINK_REVOKED";

    private final Instance<AuditLedgerService> ledger;

    @Inject
    public HealthAdminAudit(Instance<AuditLedgerService> ledger) {
        this.ledger = ledger;
    }

    public void profileSaved(String actor, String agentId) {
        submit(actor, agentId, PROFILE_SAVED, Map.of());
    }

    public void profileDeleted(String actor, String agentId) {
        submit(actor, agentId, PROFILE_DELETED, Map.of());
    }

    public void linkCreated(String actor, String agentId, String linkId, List<String> sections, String period, Instant expiresAt) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("linkId", linkId);
        output.put("sections", sections);
        output.put("period", period);
        output.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        submit(actor, agentId, LINK_CREATED, output);
    }

    /** One link revoked, or every link of the agent ({@code linkId} null). */
    public void linkRevoked(String actor, String agentId, String linkId, long count) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("linkId", linkId);
        output.put("revoked", count);
        submit(actor, agentId, LINK_REVOKED, output);
    }

    private void submit(String actor, String agentId, String action, Map<String, Object> output) {
        if (ledger == null || !ledger.isResolvable()) {
            return;
        }
        try {
            AuditLedgerService service = ledger.get();
            if (!service.isEnabled()) {
                return;
            }
            service.submit(new AuditEntry(UUID.randomUUID().toString(), null, agentId, null, actor, null, 0, TASK_ID, TASK_TYPE, 0, 0,
                    Map.of("action", action), output, null, null, List.of(action), 0.0, Instant.now(), null, null, AuditEntry.UNSEQUENCED));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not write the %s audit entry for agent %s: %s", action, sanitize(agentId), sanitize(e.getMessage()));
        }
    }
}
