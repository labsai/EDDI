/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SessionManagement Config Tests")
class SessionManagementTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValueTests {

        @Test
        @DisplayName("Should have sensible defaults")
        void testDefaults() {
            var session = new AgentConfiguration.SessionManagement();

            assertEquals(10, session.getMaxCheckpointsPerConversation());
            assertNull(session.getAutoSnapshot());
        }
    }

    @Nested
    @DisplayName("AutoSnapshot")
    class AutoSnapshotTests {

        @Test
        @DisplayName("Should have disabled default")
        void testAutoSnapshotDefaults() {
            var autoSnapshot = new AgentConfiguration.SessionManagement.AutoSnapshot();

            assertFalse(autoSnapshot.isEnabled());
            assertNotNull(autoSnapshot.getTriggerOn());
            assertTrue(autoSnapshot.getTriggerOn().isEmpty());
        }

        @Test
        @DisplayName("Should accept trigger events")
        void testAutoSnapshotTriggers() {
            var autoSnapshot = new AgentConfiguration.SessionManagement.AutoSnapshot();
            autoSnapshot.setEnabled(true);
            autoSnapshot.setTriggerOn(List.of("before_tool", "before_action"));

            assertTrue(autoSnapshot.isEnabled());
            assertEquals(2, autoSnapshot.getTriggerOn().size());
            assertEquals("before_tool", autoSnapshot.getTriggerOn().get(0));
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get all fields")
        void testGettersSetters() {
            var session = new AgentConfiguration.SessionManagement();
            var autoSnapshot = new AgentConfiguration.SessionManagement.AutoSnapshot();

            session.setAutoSnapshot(autoSnapshot);
            session.setMaxCheckpointsPerConversation(20);

            assertEquals(autoSnapshot, session.getAutoSnapshot());
            assertEquals(20, session.getMaxCheckpointsPerConversation());
        }
    }

    @Nested
    @DisplayName("AgentConfiguration Integration")
    class AgentConfigTests {

        @Test
        @DisplayName("Should attach SessionManagement to AgentConfiguration")
        void testAttachToAgent() {
            var agentConfig = new AgentConfiguration();
            var session = new AgentConfiguration.SessionManagement();
            session.setMaxCheckpointsPerConversation(25);

            agentConfig.setSessionManagement(session);

            assertNotNull(agentConfig.getSessionManagement());
            assertEquals(25, agentConfig.getSessionManagement().getMaxCheckpointsPerConversation());
        }

        @Test
        @DisplayName("Should default to null SessionManagement")
        void testDefaultNull() {
            var agentConfig = new AgentConfiguration();
            assertNull(agentConfig.getSessionManagement());
        }
    }
}
