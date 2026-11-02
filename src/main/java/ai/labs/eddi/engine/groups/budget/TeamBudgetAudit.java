/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the domain audit entry for a budget adjustment or transfer (F6).
 * <p>
 * {@code AdminActionAuditFilter} records only method, path and status, never
 * the body, so the amount and the reason would otherwise be lost. Entries are
 * signed by the ledger like any other and never fail the operation they
 * describe.
 */
@ApplicationScoped
public class TeamBudgetAudit {

    private static final Logger LOGGER = Logger.getLogger(TeamBudgetAudit.class);

    static final String ACTION_ADJUSTED = "TEAM_BUDGET_ADJUSTED";
    static final String ACTION_TRANSFERRED = "TEAM_BUDGET_TRANSFERRED";

    private AuditLedgerService ledger;

    @Inject
    public TeamBudgetAudit(AuditLedgerService ledger) {
        this.ledger = ledger;
    }

    /** For tests. */
    TeamBudgetAudit() {
    }

    void adjusted(String actor, String groupId, String month, double deltaUsd, double capBeforeUsd, double capAfterUsd, String reason) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("groupId", groupId);
        output.put("month", month);
        output.put("deltaUsd", deltaUsd);
        output.put("capBeforeUsd", capBeforeUsd);
        output.put("capAfterUsd", capAfterUsd);
        output.put("reason", reason);
        submit(actor, ACTION_ADJUSTED, output);
    }

    void transferred(String actor, String fromGroupId, String toGroupId, String month, double amountUsd, String reason, String transferId,
                     String outcome) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("fromGroupId", fromGroupId);
        output.put("toGroupId", toGroupId);
        output.put("month", month);
        output.put("amountUsd", amountUsd);
        output.put("reason", reason);
        output.put("transferId", transferId);
        output.put("outcome", outcome);
        submit(actor, ACTION_TRANSFERRED, output);
    }

    private void submit(String actor, String action, Map<String, Object> output) {
        if (ledger == null) {
            return;
        }
        try {
            ledger.submit(new AuditEntry(UUID.randomUUID().toString(), null, null, null, actor, null, 0, "ai.labs.budget", "budget", 0, 0,
                    Map.of("action", action), output, null, null, List.of(action), 0.0, Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Could not write the %s audit entry: %s", action, e.getMessage());
        }
    }
}
