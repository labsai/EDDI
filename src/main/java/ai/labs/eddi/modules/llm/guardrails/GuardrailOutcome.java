/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One guardrail that fired: the payload of the audit entry, the
 * {@code guardrail_triggered} SSE event and the
 * {@code eddi.guardrail.triggered} metric.
 *
 * @param type
 *            the guardrail type
 * @param phase
 *            input or output
 * @param action
 *            what was done
 * @param reason
 *            the guardrail's EDDI-written reason (never the screened text), or
 *            empty
 * @param retryCount
 *            how many reprompts had been sent when this outcome was recorded
 */
public record GuardrailOutcome(String type, GuardrailPhase phase, GuardrailAction action, String reason, int retryCount) {

    public GuardrailOutcome {
        reason = reason != null ? reason : "";
    }

    /**
     * The audit record: plain strings and numbers, no null members, so it
     * canonicalizes stably for the ledger HMAC.
     */
    public Map<String, Object> toAuditRecord(String taskId) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("phase", phase.label());
        record.put("type", type);
        record.put("action", action.label());
        record.put("reason", reason);
        record.put("retryCount", retryCount);
        record.put("taskId", taskId != null ? taskId : "");
        return record;
    }
}
