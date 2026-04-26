/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.model;

import ai.labs.eddi.backup.model.ImportPreview.DiffAction;
import ai.labs.eddi.backup.model.ImportPreview.ResourceDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupModelsTest {

    // ==================== ImportPreview ====================

    @Test
    void importPreview_allFieldsAccessible() {
        var diff = new ResourceDiff("src1", "agent", "My Agent",
                DiffAction.CREATE, null, null, null,
                "{\"config\":true}", null, -1);

        var preview = new ImportPreview("srcAgent", "Source Agent",
                "tgtAgent", "Target Agent", List.of(diff));

        assertEquals("srcAgent", preview.sourceAgentId());
        assertEquals("Source Agent", preview.sourceAgentName());
        assertEquals("tgtAgent", preview.targetAgentId());
        assertEquals("Target Agent", preview.targetAgentName());
        assertEquals(1, preview.resources().size());
    }

    @Test
    void resourceDiff_createAction() {
        var diff = new ResourceDiff("id1", "workflow", "Workflow 1",
                DiffAction.CREATE, null, null, null,
                "{}", null, 0);

        assertEquals("id1", diff.sourceId());
        assertEquals("workflow", diff.resourceType());
        assertEquals("Workflow 1", diff.name());
        assertEquals(DiffAction.CREATE, diff.action());
        assertNull(diff.targetId());
        assertNull(diff.targetVersion());
        assertNull(diff.matchStrategy());
        assertEquals(0, diff.workflowIndex());
    }

    @Test
    void resourceDiff_updateAction_withTarget() {
        var diff = new ResourceDiff("id1", "langchain", "LLM Config",
                DiffAction.UPDATE, "tgt1", 3, "type",
                "{\"new\":true}", "{\"old\":true}", -1);

        assertEquals(DiffAction.UPDATE, diff.action());
        assertEquals("tgt1", diff.targetId());
        assertEquals(3, diff.targetVersion());
        assertEquals("type", diff.matchStrategy());
    }

    @Test
    void diffAction_allValues() {
        assertEquals(4, DiffAction.values().length);
        assertNotNull(DiffAction.valueOf("CREATE"));
        assertNotNull(DiffAction.valueOf("UPDATE"));
        assertNotNull(DiffAction.valueOf("SKIP"));
        assertNotNull(DiffAction.valueOf("CONFLICT"));
    }

    // ==================== ExportPreview ====================

    @Test
    void exportPreview_allFieldsAccessible() {
        var resource = new ExportPreview.ExportableResource(
                "res1", 2, "langchain", "My LLM", "wf1", 0, false);

        var preview = new ExportPreview("agent1", "My Agent", 3, List.of(resource));

        assertEquals("agent1", preview.agentId());
        assertEquals("My Agent", preview.agentName());
        assertEquals(3, preview.agentVersion());
        assertEquals(1, preview.resources().size());
    }

    @Test
    void exportableResource_requiredFlag() {
        var required = new ExportPreview.ExportableResource(
                "agent1", 1, "agent", "Agent", null, -1, true);
        assertTrue(required.required());

        var optional = new ExportPreview.ExportableResource(
                "ext1", 1, "langchain", "LLM", "wf1", 0, false);
        assertFalse(optional.required());
    }

    // ==================== SyncRequest ====================

    @Test
    void syncRequest_allFieldsAccessible() {
        var request = new SyncRequest("srcAgent", 2, "tgtAgent",
                java.util.Set.of("res1", "res2"), List.of("wf1", "wf2"));

        assertEquals("srcAgent", request.sourceAgentId());
        assertEquals(2, request.sourceAgentVersion());
        assertEquals("tgtAgent", request.targetAgentId());
        assertEquals(2, request.selectedResources().size());
        assertEquals(2, request.workflowOrder().size());
    }

    @Test
    void syncRequest_nullOptionalFields() {
        var request = new SyncRequest("srcAgent", null, null, null, null);

        assertEquals("srcAgent", request.sourceAgentId());
        assertNull(request.sourceAgentVersion());
        assertNull(request.targetAgentId());
        assertNull(request.selectedResources());
        assertNull(request.workflowOrder());
    }

    // ==================== SyncMapping ====================

    @Test
    void syncMapping_allFieldsAccessible() {
        var mapping = new SyncMapping("src1", 2, "tgt1");
        assertEquals("src1", mapping.sourceAgentId());
        assertEquals(2, mapping.sourceAgentVersion());
        assertEquals("tgt1", mapping.targetAgentId());
    }

    @Test
    void syncMapping_nullOptionalFields() {
        var mapping = new SyncMapping("src1", null, null);
        assertEquals("src1", mapping.sourceAgentId());
        assertNull(mapping.sourceAgentVersion());
        assertNull(mapping.targetAgentId());
    }
}
