/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.datastore.postgres.PostgresApprovalDecisionStore;
import ai.labs.eddi.datastore.postgres.PostgresAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.hitl.oversight.analytics.mongo.MongoApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Evidence;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Recommendation;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Suspension;
import ai.labs.eddi.engine.hitl.oversight.autonomy.mongo.MongoAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import ai.labs.eddi.engine.hitl.oversight.samples.mongo.MongoAuditSampleStore;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.sample;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Serializer-level round trips of the three oversight stores (Docker-backed
 * store ITs are not part of this change): the JSON payload both backends keep,
 * the MongoDB document mapping, and the PostgreSQL WHERE builders.
 */
@DisplayName("oversight store serialization")
class OversightStoreSerializationTest {

    @Test
    @DisplayName("a decision row round-trips; userId and decidedBy live only in their columns")
    void decisionRow() {
        ApprovalDecision row = OversightFixtures.decision("APPROVED", NOW);

        String json = OversightJson.write(row.withoutPersonalFields());
        ApprovalDecision back = OversightJson.read(json, ApprovalDecision.class).withPersonalFields(row.userId(), row.decidedBy());

        assertEquals(row, back);
        assertFalse(json.contains("user-1"), json);
        assertFalse(json.contains("alice"), json);
    }

    @Test
    @DisplayName("a sample round-trips; status, version, user and reviewer live only in their columns")
    void sampleRow() {
        AuditSample s = sample("s1").reviewed(AuditSample.Status.FLAGGED, "TONE", "rude", "erin", NOW);

        String json = OversightJson.write(s.payload());
        AuditSample back = OversightJson.read(json, AuditSample.class).withColumns(s.userId(), s.reviewedBy(), s.status(), s.version());

        assertEquals(s, back);
        assertFalse(json.contains("erin"), json);
    }

    @Test
    @DisplayName("an autonomy state with a recommendation and a suspension round-trips")
    void state() {
        var evidence = new Evidence(30, 28, 1, 1, 0.9333, NOW.minusSeconds(100), NOW);
        var recommendation = new Recommendation("r1", Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN, NOW, 3, evidence, "p", true,
                Map.of("toolApprovals", Map.of("exempt", List.of("p"))), List.of("Add 'p' to exempt"), false, null, null, null, null, null,
                null);
        var suspension = new Suspension(true, "p", NOW, Suspension.Trigger.REASON_CODE, "s1", "FALSE_CLAIM", "system:audit-sample", null,
                null, null);
        AutonomyState state = AutonomyState.initial("agent-1", "tool:p").withRecommendation(recommendation).withSuspension(suspension)
                .withEvidenceResetAt(NOW).withVersion(7);

        AutonomyState back = OversightJson.read(OversightJson.write(state), AutonomyState.class);

        assertEquals(state, back);
    }

    @Test
    @DisplayName("MongoDB documents keep the indexed fields top-level and read back the same row")
    void mongoDocuments() {
        ApprovalDecision row = decision("REJECTED", NOW);
        Document doc = MongoApprovalDecisionStore.toDocument(row);
        assertEquals(NOW.toEpochMilli(), doc.get("decidedAt"));
        assertEquals("alice", doc.get("decidedBy"));
        assertEquals(row, MongoApprovalDecisionStore.fromDocument(doc));

        AuditSample s = sample("s1");
        Document sampleDoc = MongoAuditSampleStore.toDocument(s);
        assertEquals("PENDING", sampleDoc.get("status"));
        assertEquals(s, MongoAuditSampleStore.fromDocument(sampleDoc));

        AutonomyState state = AutonomyState.initial("agent-1", "tool:p").withVersion(2);
        assertEquals(state, MongoAutonomyStateStore.fromDocument(MongoAutonomyStateStore.toDocument(state)));
    }

    @Test
    @DisplayName("PostgreSQL WHERE clauses bind exactly the given filter fields, in order")
    void postgresWhere() {
        List<Object> params = new ArrayList<>();
        String where = PostgresApprovalDecisionStore.where(new DecisionFilter("a", null, "c", NOW, null, "regular", null, "SLACK", 3), params);
        assertEquals(" WHERE agent_id = ? AND category = ? AND decided_at >= ? AND surface = ? AND channel = ? AND agent_version = ?", where);
        assertEquals(List.of("a", "c", NOW.toEpochMilli(), "regular", "SLACK", 3), params);

        List<Object> none = new ArrayList<>();
        assertEquals("", PostgresApprovalDecisionStore.where(new DecisionFilter(null, null, null, null, null, null, null, null, null), none));

        List<Object> sp = new ArrayList<>();
        assertEquals(" WHERE agent_id = ? AND status = ?", PostgresAuditSampleStore.where(SampleFilter.pending("a"), sp));
        assertEquals(List.of("a", "PENDING"), sp);
    }
}
