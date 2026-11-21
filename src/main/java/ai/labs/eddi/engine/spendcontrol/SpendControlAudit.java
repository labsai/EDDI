/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the audit-ledger entry for every spend-control write and decision:
 * {@code taskType: spendcontrol}, the acting principal as {@code userId}, and
 * the reason, the changes and the channel in {@code output}. Like
 * {@code TeamBudgetAudit}, an entry never fails the operation it describes.
 */
@ApplicationScoped
public class SpendControlAudit {

    private static final Logger LOGGER = Logger.getLogger(SpendControlAudit.class);

    /** A tightening change applied directly. */
    public static final String APPLIED = "SPEND_CONTROL_APPLIED";
    /** A loosening change filed for approval. */
    public static final String PROPOSED = "SPEND_CONTROL_PROPOSED";
    /** A proposal approved and applied — the approver is the actor. */
    public static final String APPROVED = "SPEND_CONTROL_APPROVED";
    public static final String REJECTED = "SPEND_CONTROL_REJECTED";
    public static final String WITHDRAWN = "SPEND_CONTROL_WITHDRAWN";
    /** Approved, but the control had changed meanwhile: nothing applied. */
    public static final String STALE = "SPEND_CONTROL_STALE";
    /** Approved, but the write failed: whether it took effect is unknown. */
    public static final String FAILED = "SPEND_CONTROL_FAILED";

    private AuditLedgerService ledger;

    @Inject
    public SpendControlAudit(AuditLedgerService ledger) {
        this.ledger = ledger;
    }

    /** For tests. */
    SpendControlAudit() {
    }

    /**
     * @param action
     *            one of the constants
     * @param actor
     *            who acted
     * @param output
     *            the entry's details (kind, target, reason, changes, …)
     */
    public void record(String action, String actor, Map<String, Object> output) {
        if (ledger == null) {
            return;
        }
        try {
            ledger.submit(new AuditEntry(UUID.randomUUID().toString(), null, null, null, actor, null, 0, "ai.labs.spendcontrol", "spendcontrol", 0,
                    0, Map.of("action", action), output, null, null, List.of(action), 0.0, Instant.now(), null, null));
        } catch (Exception e) {
            LOGGER.warnf("Could not write the %s audit entry: %s", action, LogSanitizer.sanitize(e.getMessage()));
        }
    }
}
