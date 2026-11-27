/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent Health: the cascade leaves every failed step on the conversation step,
 * recovered or not — and its recovery stays exactly what it was.
 */
@DisplayName("CascadingModelExecutor — failed steps recorded for Agent Health")
class CascadingModelExecutorHealthRecordTest {

    private static final String DAILY_QUOTA = "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"quota\","
            + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":"
            + "\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}";

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        task.setRetry(retry);
        return task;
    }

    private static List<ChatMessage> messages() {
        var m = new ArrayList<ChatMessage>();
        m.add(SystemMessage.from("sys"));
        m.add(UserMessage.from("hi"));
        return m;
    }

    private static ModelCascadeConfig twoSteps() {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var first = new CascadeStep();
        first.setType("openai");
        first.setConfidenceThreshold(0.5);
        var second = new CascadeStep();
        second.setType("anthropic");
        cascade.setSteps(List.of(first, second));
        return cascade;
    }

    private static ChatModel failing() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenThrow(new RateLimitException(new HttpException(429, DAILY_QUOTA)));
        return model;
    }

    private static ChatModel answering(String text) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyList())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
        return model;
    }

    private static CascadingModelExecutor.CascadeResult run(ChatModel first, ChatModel second, ConversationMemory memory) throws Exception {
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("openai"), anyMap())).thenReturn(first);
        when(registry.getOrCreate(eq("anthropic"), anyMap())).thenReturn(second);
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(),
                new SimpleMeterRegistry(), new CallerIdentityContext(null, null));
        return executor.execute(twoSteps(), messages(), "sys", Map.of("apiKey", "k"), task(), memory, mock(AgentOrchestrator.class), Map.of(),
                false, false, false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(ConversationMemory memory) {
        IData<Object> data = memory.getCurrentStep().getData(MemoryKeys.HEALTH_LLM_FAILURES);
        return data == null ? null : (List<Map<String, Object>>) data.getResult();
    }

    @Test
    @DisplayName("an escalated step is recorded as recovered by the cascade, with its class and model; the answer is unchanged")
    void escalationIsRecovered() throws Exception {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");

        var result = run(failing(), answering("recovered"), memory);

        assertEquals("recovered", result.response());
        List<Map<String, Object>> records = records(memory);
        assertEquals(1, records.size());
        assertEquals("QUOTA_EXHAUSTED", records.get(0).get(HealthFailure.KEY_CLASS));
        assertEquals(HealthFailure.CASCADE, records.get(0).get(HealthFailure.KEY_RECOVERY));
        assertNotNull(records.get(0).get(HealthFailure.KEY_MODEL));
    }

    @Test
    @DisplayName("an exhausted cascade leaves its last failure pending for the task to settle")
    void exhaustedIsPending() {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");

        assertThrows(LifecycleException.class, () -> run(failing(), failing(), memory));

        List<Map<String, Object>> records = records(memory);
        assertEquals(2, records.size());
        assertEquals(HealthFailure.CASCADE, records.get(0).get(HealthFailure.KEY_RECOVERY));
        assertEquals(LlmFailureRecords.PENDING, records.get(1).get(HealthFailure.KEY_RECOVERY));
    }

    @Test
    @DisplayName("a clean cascade records nothing")
    void cleanRecordsNothing() throws Exception {
        ConversationMemory memory = new ConversationMemory("c", "a", 1, "u");
        run(answering("fine"), answering("unused"), memory);
        assertNull(records(memory));
    }
}
