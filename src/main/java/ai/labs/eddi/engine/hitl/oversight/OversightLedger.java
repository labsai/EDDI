/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

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
 * Writes the audit-ledger entries of oversight actions. Every human verdict on
 * a sample and every change to a category's autonomy is evidence, so it goes to
 * the ledger like an approval does ({@code taskType: hitl}). Never throws.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class OversightLedger {

    private static final Logger LOGGER = Logger.getLogger(OversightLedger.class);

    /** A reviewer's verdict on an audit sample. */
    public static final String SAMPLE_REVIEW = "hitl.sample.review";
    /** A recommendation applied: the category no longer asks. */
    public static final String PROMOTION = "autonomy.promotion";
    /** A recommendation applied: a suspension made permanent in configuration. */
    public static final String REVERT = "autonomy.revert";
    /** A recommendation dismissed. */
    public static final String DISMISSAL = "autonomy.recommendation.dismissed";
    /** A category suspended (automatically or by hand). */
    public static final String DEMOTION = "autonomy.demotion";
    /** A suspension lifted. */
    public static final String LIFTED = "autonomy.suspension.lifted";
    static final String TASK_TYPE = "hitl";

    private final AuditLedgerService ledger;

    @Inject
    public OversightLedger(AuditLedgerService ledger) {
        this.ledger = ledger;
    }

    /**
     * Writes one entry.
     *
     * @param conversationId
     *            the conversation it concerns, or null
     * @param actor
     *            who acted (recorded as the entry's user)
     */
    public void write(String taskId, String conversationId, String agentId, Integer agentVersion, String actor, Map<String, Object> details) {
        if (ledger == null || !ledger.isEnabled()) {
            return;
        }
        try {
            var output = new LinkedHashMap<String, Object>(details);
            output.put("actor", actor);
            ledger.submit(new AuditEntry(UUID.randomUUID().toString(), conversationId, agentId, agentVersion, actor, null, -1, taskId,
                    TASK_TYPE, -1, 0L, Map.of(), output, null, null, List.of(), 0.0, Instant.now(), null, null));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not write the %s audit entry for agent %s: %s", taskId, agentId, e.getMessage());
        }
    }
}
