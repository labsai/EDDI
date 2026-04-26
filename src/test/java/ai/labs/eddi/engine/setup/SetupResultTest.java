/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SetupResult Tests")
class SetupResultTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("builds with all fields")
        void allFields() {
            var result = SetupResult.builder()
                    .action("setup_complete")
                    .agentId("agent1")
                    .agentName("My Agent")
                    .provider("openai")
                    .model("gpt-4o")
                    .deployed(true)
                    .deploymentStatus("READY")
                    .endpointCount(3)
                    .groups(List.of("group1"))
                    .quickRepliesEnabled(true)
                    .sentimentAnalysisEnabled(false)
                    .resources(Map.of("llm", "/llmstore/llms/llm1"))
                    .build();

            assertEquals("setup_complete", result.action());
            assertEquals("agent1", result.agentId());
            assertEquals("My Agent", result.agentName());
            assertEquals("openai", result.provider());
            assertEquals("gpt-4o", result.model());
            assertTrue(result.deployed());
            assertEquals("READY", result.deploymentStatus());
            assertEquals(3, result.endpointCount());
            assertEquals(List.of("group1"), result.groups());
            assertTrue(result.quickRepliesEnabled());
            assertFalse(result.sentimentAnalysisEnabled());
            assertEquals("/llmstore/llms/llm1", result.resources().get("llm"));
        }

        @Test
        @DisplayName("builds with minimal fields")
        void minimalFields() {
            var result = SetupResult.builder()
                    .action("setup_complete")
                    .agentId("agent1")
                    .build();

            assertEquals("setup_complete", result.action());
            assertEquals("agent1", result.agentId());
            assertNull(result.agentName());
            assertNull(result.provider());
            assertNull(result.model());
            assertNull(result.deployed());
        }

        @Test
        @DisplayName("builder is fluent")
        void builderFluent() {
            var builder = SetupResult.builder();
            assertSame(builder, builder.action("test"));
            assertSame(builder, builder.agentId("id"));
            assertSame(builder, builder.agentName("name"));
        }
    }

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("same values are equal")
        void sameValuesEqual() {
            var a = SetupResult.builder().action("a").agentId("1").build();
            var b = SetupResult.builder().action("a").agentId("1").build();
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different values are not equal")
        void differentValues() {
            var a = SetupResult.builder().action("a").agentId("1").build();
            var b = SetupResult.builder().action("b").agentId("2").build();
            assertNotEquals(a, b);
        }
    }
}
