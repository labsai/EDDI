/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedActionFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedActionType;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionRecord;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionStatus;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stored documents written before approved-result actions existed must still
 * load, and the new fields must survive a round trip — the same JSON document
 * goes to MongoDB and to PostgreSQL's JSONB column.
 */
class ApprovedActionSerializationTest {

    private final JsonSerialization json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));

    @Test
    void anOldDiscussionDocument_withATaskListButNoActions_loads() throws Exception {
        String old = """
                {"id":"gc-old","groupId":"g","state":"IN_PROGRESS",
                 "taskList":{"tasks":[{"id":"t1","subject":"s","description":"d","status":"COMPLETED","dependsOnIds":[],
                   "result":"r","verified":false,"priority":0}],"reviewerEdits":{}}}
                """;

        GroupConversation gc = json.deserialize(old, GroupConversation.class);

        assertNotNull(gc.getTaskList().getApprovedActions());
        assertTrue(gc.getTaskList().getApprovedActions().isEmpty());
        assertTrue(gc.getTaskList().recordApprovedAction("t1", record()), "an old document can owe an action after the upgrade");
    }

    @Test
    void anOldPhase_withoutOnApproved_loads() throws Exception {
        String phase = """
                {"name":"Draft","type":"EXECUTE","participants":"ALL","turnOrder":"SEQUENTIAL","contextScope":"FULL",
                 "repeats":1,"requiresApproval":true}
                """;

        var parsed = json.deserialize(phase, AgentGroupConfiguration.DiscussionPhase.class);

        assertNull(parsed.onApproved());
        assertTrue(parsed.requiresApproval());
    }

    @Test
    void aPhaseWithOnApproved_defaultsTypeAndPolicy() throws Exception {
        String phase = """
                {"name":"Draft","type":"EXECUTE","requiresApproval":true,
                 "onApproved":{"action":{"uri":"eddi://ai.labs.apicalls/apicallstore/apicalls/abc?version=1","call":"post"}}}
                """;

        var parsed = json.deserialize(phase, AgentGroupConfiguration.DiscussionPhase.class);

        assertEquals(ApprovedActionType.HTTPCALL, parsed.onApproved().action().type());
        assertEquals(ApprovedActionFailurePolicy.FAIL_TASK, parsed.onApproved().onFailure());
        assertEquals("post", parsed.onApproved().action().call());
    }

    @Test
    void theActionRecord_survivesARoundTrip() throws Exception {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        var list = new SharedTaskList();
        list.recordApprovedAction("t1", record().running(Instant.parse("2026-11-09T10:00:01Z"))
                .finished(ApprovedActionStatus.SUCCEEDED, Instant.parse("2026-11-09T10:00:02Z"), 201, "https://x/1", "HTTP 201"));
        gc.setTaskList(list);

        GroupConversation back = json.deserialize(json.serialize(gc), GroupConversation.class);

        var record = back.getTaskList().getApprovedActions().get("t1");
        assertEquals(ApprovedActionStatus.SUCCEEDED, record.status());
        assertEquals(201, record.httpCode());
        assertEquals("https://x/1", record.responseUrl());
        assertEquals(ApprovedActionFailurePolicy.KEEP_COMPLETED, record.onFailure());
        assertEquals(Instant.parse("2026-11-09T10:00:01Z"), record.startedAt());
        assertTrue(record.edited());
    }

    private static ApprovedActionRecord record() {
        return new ApprovedActionRecord(ApprovedActionStatus.PENDING, "Draft", 1, "eddi://x#post", ApprovedActionFailurePolicy.KEEP_COMPLETED,
                "alice", true, "k", Instant.parse("2026-11-09T10:00:00Z"), null, null, null, null, null);
    }
}
