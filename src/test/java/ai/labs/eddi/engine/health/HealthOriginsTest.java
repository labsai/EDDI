/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HealthOrigins — where a turn came from")
class HealthOriginsTest {

    private static IWritableConversationStep step(String... contextKeyValues) {
        IWritableConversationStep step = new ConversationMemory("c", "a", 1, "u").getCurrentStep();
        for (int i = 0; i + 1 < contextKeyValues.length; i += 2) {
            step.storeData(new Data<>("context:" + contextKeyValues[i], new Context(Context.ContextType.string, contextKeyValues[i + 1])));
        }
        return step;
    }

    @ParameterizedTest(name = "user {0} → {1}")
    @CsvSource({"agent-test:run1:case, agent-test", "system:scheduler, schedule", "a2a:anonymous, a2a", "email:integration:a@b.c, email",
            "email-anon:abc, email", "slack:U123, slack", "webhook:hook1, webhook", "some-oidc-user, api"})
    void byUserId(String userId, String origin) {
        assertEquals(origin, HealthOrigins.classify(userId, step()));
    }

    @ParameterizedTest(name = "context {0}={1} → {2}")
    @CsvSource({"groupConversationId, g1, group-member", "delegationDepth, 1, delegated", "schedule, x, schedule",
            "scheduleMaxCostPerFire, 0.5, schedule", "a2aTaskId, t1, a2a", "emailIntegration, i1, email", "slack, true, slack",
            "webhookIntegration, w1, webhook", "channel, openai, v1", "channelIntent, channel:openai:agent:chat, v1", "mcp, true, mcp",
            "channelIntent, channel:slack:C1, api", "channel, other, api", "language, en, api"})
    void byContext(String key, String value, String origin) {
        assertEquals(origin, HealthOrigins.classify("plain-user", step(key, value)));
    }

    @Test
    @DisplayName("first match wins: an agent test of a group member is an agent test; a group member's schedule is a group member")
    void precedence() {
        assertEquals(HealthOrigins.AGENT_TEST, HealthOrigins.classify("agent-test:r:c", step("groupConversationId", "g")));
        assertEquals(HealthOrigins.GROUP_MEMBER, HealthOrigins.classify("system:scheduler", step("groupConversationId", "g")));
        assertEquals(HealthOrigins.DELEGATED, HealthOrigins.classify("slack:U1", step("delegationDepth", "2")));
    }

    @Test
    @DisplayName("no step and no user is api, never null")
    void nulls() {
        assertEquals(HealthOrigins.API, HealthOrigins.classify(null, null));
    }

    @Test
    @DisplayName("every origin is listed, and the interactive ones are origins")
    void lists() {
        assertEquals(11, HealthOrigins.ALL.size());
        assertTrue(HealthOrigins.ALL.containsAll(HealthOrigins.INTERACTIVE));
    }

    /**
     * The context keys written as literals (their constants are not public) must
     * still be what the integrations write.
     */
    @Test
    @DisplayName("the literal context keys and prefixes match the integrations that write them")
    void literalsMatchTheirWriters() throws Exception {
        Path src = Path.of("src", "main", "java", "ai", "labs", "eddi");
        assertTrue(Files.readString(src.resolve("engine/runtime/internal/ScheduleFireExecutor.java")).contains("contextMap.put(\""
                + HealthOrigins.CONTEXT_SCHEDULE + "\""));
        assertTrue(Files.readString(src.resolve("engine/a2a/A2ATaskHandler.java")).contains("CONTEXT_TASK_ID = \"" + HealthOrigins.CONTEXT_A2A_TASK
                + "\""));
        assertTrue(Files.readString(src.resolve("engine/a2a/A2ATaskHandler.java")).contains("\"" + HealthOrigins.A2A_USER_PREFIX + "anonymous\""));
        assertTrue(Files.readString(src.resolve("integrations/slack/SlackEventHandler.java")).contains("Map.of(\"" + HealthOrigins.CONTEXT_SLACK
                + "\""));
        assertTrue(Files.readString(src.resolve("engine/mcp/McpConversationTools.java")).contains("Map.of(\"" + HealthOrigins.CONTEXT_MCP + "\""));
        assertTrue(Files.readString(src.resolve("integrations/webhook/WebhookEventHandler.java")).contains("\""
                + HealthOrigins.CONTEXT_WEBHOOK_INTEGRATION + "\""));
        String bridge = Files.readString(src.resolve("integrations/openai/OpenAiConversationBridge.java"));
        assertTrue(bridge.contains("CONTEXT_CHANNEL_INTENT = \"" + HealthOrigins.CONTEXT_CHANNEL_INTENT + "\""));
        assertTrue(bridge.contains("INTENT_PREFIX = \"" + HealthOrigins.OPENAI_INTENT_PREFIX + "\""));
    }
}
