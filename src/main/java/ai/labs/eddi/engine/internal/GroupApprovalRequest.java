/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.Map;

/**
 * REST request body for group discussion approval.
 */
public class GroupApprovalRequest {
    // #39: the sibling taskApprovals values are deliberately case-insensitive, so
    // the decision.verdict must be too — otherwise {"verdict":"approved"} fails
    // Jackson enum deserialization while {"t1":"approved"} two lines down is
    // accepted. Scoped to the group surface via this request-local deserializer.
    @JsonDeserialize(using = HitlDecisionDeserializer.class)
    private HitlDecision decision;
    /** taskId → "APPROVED" or "REJECTED" */
    private Map<String, String> taskApprovals;

    public HitlDecision getDecision() {
        return decision;
    }

    public void setDecision(HitlDecision decision) {
        this.decision = decision;
    }

    public Map<String, String> getTaskApprovals() {
        return taskApprovals;
    }

    public void setTaskApprovals(Map<String, String> taskApprovals) {
        this.taskApprovals = taskApprovals;
    }

    /**
     * Case-insensitive deserializer for the approval {@link HitlDecision} so the
     * verdict accepts any casing (APPROVED/approved/Approved), matching the
     * case-insensitive taskApprovals values in the same body (#39). An unrecognized
     * verdict yields a null verdict, which the REST layer reports as the friendly
     * 400 ("must include a 'verdict' field") rather than a raw Jackson enum error.
     */
    public static class HitlDecisionDeserializer extends JsonDeserializer<HitlDecision> {
        @Override
        public HitlDecision deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node == null || node.isNull()) {
                return null;
            }
            var decision = new HitlDecision();
            JsonNode verdictNode = node.get("verdict");
            if (verdictNode != null && !verdictNode.isNull()) {
                try {
                    decision.setVerdict(HitlDecision.HitlVerdict.valueOf(
                            verdictNode.asText().trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    decision.setVerdict(null); // reported as the friendly 400 upstream
                }
            }
            JsonNode noteNode = node.get("note");
            if (noteNode != null && !noteNode.isNull()) {
                decision.setNote(noteNode.asText());
            }
            JsonNode decidedByNode = node.get("decidedBy");
            if (decidedByNode != null && !decidedByNode.isNull()) {
                decision.setDecidedBy(decidedByNode.asText());
            }
            return decision;
        }
    }
}
