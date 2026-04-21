package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.model.Deployment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimpleConversationMemorySnapshot and its inner types.
 */
class SimpleConversationMemorySnapshotTest {

    @Test
    @DisplayName("defaults are non-null collections")
    void defaults() {
        var snapshot = new SimpleConversationMemorySnapshot();

        assertNull(snapshot.getConversationId());
        assertNull(snapshot.getAgentId());
        assertNull(snapshot.getAgentVersion());
        assertNull(snapshot.getUserId());
        assertNull(snapshot.getEnvironment());
        assertNull(snapshot.getConversationState());
        assertFalse(snapshot.isUndoAvailable());
        assertFalse(snapshot.isRedoAvailable());
        assertNotNull(snapshot.getConversationOutputs());
        assertNotNull(snapshot.getConversationProperties());
        assertNotNull(snapshot.getConversationSteps());
    }

    @Test
    @DisplayName("round-trip all fields")
    void roundTrip() {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationId("conv-1");
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(3);
        snapshot.setUserId("user-42");
        snapshot.setEnvironment(Deployment.Environment.production);
        snapshot.setConversationState(ConversationState.ENDED);
        snapshot.setUndoAvailable(true);
        snapshot.setRedoAvailable(true);

        assertEquals("conv-1", snapshot.getConversationId());
        assertEquals("agent-1", snapshot.getAgentId());
        assertEquals(3, snapshot.getAgentVersion());
        assertEquals("user-42", snapshot.getUserId());
        assertEquals(Deployment.Environment.production, snapshot.getEnvironment());
        assertEquals(ConversationState.ENDED, snapshot.getConversationState());
        assertTrue(snapshot.isUndoAvailable());
        assertTrue(snapshot.isRedoAvailable());
    }

    @Test
    @DisplayName("set/get conversationOutputs")
    void conversationOutputs() {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("input", "Hello");
        snapshot.setConversationOutputs(List.of(output));

        assertEquals(1, snapshot.getConversationOutputs().size());
    }

    @Test
    @DisplayName("set/get conversationSteps")
    void conversationSteps() {
        var snapshot = new SimpleConversationMemorySnapshot();
        var step = new SimpleConversationMemorySnapshot.SimpleConversationStep();
        step.setTimestamp(new Date());
        snapshot.setConversationSteps(List.of(step));

        assertEquals(1, snapshot.getConversationSteps().size());
        assertNotNull(snapshot.getConversationSteps().getFirst().getTimestamp());
    }

    // ==================== SimpleConversationStep ====================

    @Nested
    @DisplayName("SimpleConversationStep")
    class SimpleConversationStepTests {

        @Test
        void defaults() {
            var step = new SimpleConversationMemorySnapshot.SimpleConversationStep();
            assertNotNull(step.getConversationStep());
            assertTrue(step.getConversationStep().isEmpty());
            assertNull(step.getTimestamp());
        }

        @Test
        void roundTrip() {
            var step = new SimpleConversationMemorySnapshot.SimpleConversationStep();
            var now = new Date();
            step.setTimestamp(now);
            var data = new SimpleConversationMemorySnapshot.ConversationStepData("key1", "value1", now, "wf-1");
            step.setConversationStep(List.of(data));

            assertEquals(now, step.getTimestamp());
            assertEquals(1, step.getConversationStep().size());
        }
    }

    // ==================== ConversationStepData ====================

    @Nested
    @DisplayName("ConversationStepData")
    class ConversationStepDataTests {

        @Test
        void constructorAndGetters() {
            var now = new Date();
            var data = new SimpleConversationMemorySnapshot.ConversationStepData(
                    "input", "Hello", now, "wf-42");

            assertEquals("input", data.getKey());
            assertEquals("Hello", data.getValue());
            assertEquals(now, data.getTimestamp());
            assertEquals("wf-42", data.getOriginWorkflowId());
        }

        @Test
        void setters() {
            var data = new SimpleConversationMemorySnapshot.ConversationStepData(
                    "k", "v", null, null);
            var later = new Date();
            data.setKey("newKey");
            data.setValue(42);
            data.setTimestamp(later);
            data.setOriginWorkflowId("wf-99");

            assertEquals("newKey", data.getKey());
            assertEquals(42, data.getValue());
            assertEquals(later, data.getTimestamp());
            assertEquals("wf-99", data.getOriginWorkflowId());
        }
    }
}
