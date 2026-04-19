package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.model.Deployment;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemorySnapshotTest {

    @Test
    void gettersAndSetters_work() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setId("conv-1");
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(2);
        snapshot.setUserId("user-1");
        snapshot.setEnvironment(Deployment.Environment.production);
        snapshot.setConversationState(ConversationState.READY);

        assertEquals("conv-1", snapshot.getId());
        assertEquals("conv-1", snapshot.getConversationId());
        assertEquals("agent-1", snapshot.getAgentId());
        assertEquals(2, snapshot.getAgentVersion());
        assertEquals("user-1", snapshot.getUserId());
        assertEquals(Deployment.Environment.production, snapshot.getEnvironment());
        assertEquals(ConversationState.READY, snapshot.getConversationState());
    }

    @Test
    void conversationOutputs_initiallyEmpty() {
        var snapshot = new ConversationMemorySnapshot();
        assertNotNull(snapshot.getConversationOutputs());
        assertTrue(snapshot.getConversationOutputs().isEmpty());
    }

    @Test
    void conversationProperties_initiallyEmpty() {
        var snapshot = new ConversationMemorySnapshot();
        assertNotNull(snapshot.getConversationProperties());
        assertTrue(snapshot.getConversationProperties().isEmpty());
    }

    @Test
    void conversationSteps_initiallyEmpty() {
        var snapshot = new ConversationMemorySnapshot();
        assertNotNull(snapshot.getConversationSteps());
        assertTrue(snapshot.getConversationSteps().isEmpty());
    }

    @Test
    void redoCache_initiallyEmpty() {
        var snapshot = new ConversationMemorySnapshot();
        assertNotNull(snapshot.getRedoCache());
        assertTrue(snapshot.getRedoCache().isEmpty());
    }

    @Test
    void setConversationId_setsIdViaBothPaths() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId("conv-A");
        assertEquals("conv-A", snapshot.getId());
    }

    @Test
    void equals_sameSteps_returnsTrue() {
        var s1 = new ConversationMemorySnapshot();
        var s2 = new ConversationMemorySnapshot();
        assertEquals(s1, s2);
    }

    @Test
    void equals_differentSteps_returnsFalse() {
        var s1 = new ConversationMemorySnapshot();
        var s2 = new ConversationMemorySnapshot();
        s2.getConversationSteps().add(new ConversationMemorySnapshot.ConversationStepSnapshot());
        assertNotEquals(s1, s2);
    }

    // --- ConversationStepSnapshot ---

    @Test
    void conversationStepSnapshot_workflowsInitiallyEmpty() {
        var step = new ConversationMemorySnapshot.ConversationStepSnapshot();
        assertNotNull(step.getWorkflows());
        assertTrue(step.getWorkflows().isEmpty());
    }

    @Test
    void conversationStepSnapshot_equals() {
        var s1 = new ConversationMemorySnapshot.ConversationStepSnapshot();
        var s2 = new ConversationMemorySnapshot.ConversationStepSnapshot();
        assertEquals(s1, s2);
    }

    // --- WorkflowRunSnapshot ---

    @Test
    void workflowRunSnapshot_lifecycleTasksInitiallyEmpty() {
        var run = new ConversationMemorySnapshot.WorkflowRunSnapshot();
        assertNotNull(run.getLifecycleTasks());
        assertTrue(run.getLifecycleTasks().isEmpty());
    }

    // --- ResultSnapshot ---

    @Test
    void resultSnapshot_constructorSetsFields() {
        var ts = new Date();
        var rs = new ConversationMemorySnapshot.ResultSnapshot(
                "key1", "result1", List.of("r1"), ts, "wf-1", true);
        assertEquals("key1", rs.getKey());
        assertEquals("result1", rs.getResult());
        assertEquals(ts, rs.getTimestamp());
        assertEquals("wf-1", rs.getOriginWorkflowId());
        assertTrue(rs.isPublic());
        assertTrue(rs.isCommitted());
    }

    @Test
    void resultSnapshot_constructorWithCommitted() {
        var rs = new ConversationMemorySnapshot.ResultSnapshot(
                "key1", "val", List.of(), new Date(), "wf-1", false, false);
        assertFalse(rs.isPublic());
        assertFalse(rs.isCommitted());
    }

    @Test
    void resultSnapshot_setters() {
        var rs = new ConversationMemorySnapshot.ResultSnapshot();
        rs.setKey("k");
        rs.setResult("v");
        rs.setPublic(true);
        rs.setCommitted(false);
        rs.setOriginWorkflowId("wf");
        rs.setTimestamp(new Date(1000L));
        rs.setPossibleResults(List.of("a", "b"));

        assertEquals("k", rs.getKey());
        assertEquals("v", rs.getResult());
        assertTrue(rs.isPublic());
        assertFalse(rs.isCommitted());
        assertEquals("wf", rs.getOriginWorkflowId());
        assertEquals(2, rs.getPossibleResults().size());
    }

    @Test
    void resultSnapshot_equals_sameKey_true() {
        var rs1 = new ConversationMemorySnapshot.ResultSnapshot(
                "key1", "val1", List.of("a"), new Date(), "wf", true);
        var rs2 = new ConversationMemorySnapshot.ResultSnapshot(
                "key1", "val2", List.of("a"), new Date(), "wf2", false);
        assertEquals(rs1, rs2);
    }

    @Test
    void resultSnapshot_equals_differentKey_false() {
        var rs1 = new ConversationMemorySnapshot.ResultSnapshot(
                "key1", "val", List.of(), new Date(), "wf", true);
        var rs2 = new ConversationMemorySnapshot.ResultSnapshot(
                "key2", "val", List.of(), new Date(), "wf", true);
        assertNotEquals(rs1, rs2);
    }

    @Test
    void resultSnapshot_toString_containsKey() {
        var rs = new ConversationMemorySnapshot.ResultSnapshot(
                "myKey", "myVal", List.of(), new Date(), "wf-1", true);
        String str = rs.toString();
        assertTrue(str.contains("myKey"));
        assertTrue(str.contains("myVal"));
    }
}
