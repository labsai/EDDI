/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKey;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MoreConditionsTest {

    // --- Negation ---

    @Nested
    class NegationTests {

        @Test
        void id() {
            assertEquals("negation", new Negation().getId());
        }

        @Test
        void execute_noCondition_returnsNotExecuted() throws Exception {
            var neg = new Negation();
            var result = neg.execute(mock(IConversationMemory.class), new ArrayList<>());
            assertEquals(ExecutionState.NOT_EXECUTED, result);
        }

        @Test
        void execute_successBecomeFail() throws Exception {
            var neg = new Negation();
            var inner = mock(IRuleCondition.class);
            when(inner.execute(any(), any())).thenReturn(ExecutionState.SUCCESS);
            neg.setCondition(inner);

            var result = neg.execute(mock(IConversationMemory.class), new ArrayList<>());
            assertEquals(ExecutionState.FAIL, result);
        }

        @Test
        void execute_failBecomeSuccess() throws Exception {
            var neg = new Negation();
            var inner = mock(IRuleCondition.class);
            when(inner.execute(any(), any())).thenReturn(ExecutionState.FAIL);
            neg.setCondition(inner);

            var result = neg.execute(mock(IConversationMemory.class), new ArrayList<>());
            assertEquals(ExecutionState.SUCCESS, result);
        }

        @Test
        void setConditions_singleElement() {
            var neg = new Negation();
            var inner = mock(IRuleCondition.class);
            neg.setConditions(List.of(inner));

            assertEquals(1, neg.getConditions().size());
        }

        @Test
        void setConditions_multipleElements_ignored() {
            var neg = new Negation();
            var c1 = mock(IRuleCondition.class);
            var c2 = mock(IRuleCondition.class);
            neg.setConditions(List.of(c1, c2));
            // Should not set condition since size != 1
        }

        @Test
        void clone_preservesCondition() throws Exception {
            var neg = new Negation();
            var inner = mock(IRuleCondition.class);
            when(inner.clone()).thenReturn(inner);
            neg.setCondition(inner);

            var cloned = (Negation) neg.clone();
            assertNotSame(neg, cloned);
        }
    }

    // --- ActionMatcher ---

    @Nested
    class ActionMatcherTests {

        @Test
        void id() {
            assertEquals("actionmatcher", new ActionMatcher().getId());
        }

        @Test
        void defaultActions_empty() {
            var matcher = new ActionMatcher();
            assertTrue(matcher.getActions().isEmpty());
        }

        @Test
        void setConfigs_setsActions() {
            var matcher = new ActionMatcher();
            matcher.setConfigs(Map.of("actions", "greet, farewell"));
            assertEquals(2, matcher.getActions().size());
            assertEquals("greet", matcher.getActions().get(0));
            assertEquals("farewell", matcher.getActions().get(1));
        }

        @Test
        void setConfigs_null_noOp() {
            assertDoesNotThrow(() -> new ActionMatcher().setConfigs(null));
        }

        @Test
        void getConfigs_includesActions() {
            var matcher = new ActionMatcher();
            matcher.setActions(List.of("greet", "farewell"));
            Map<String, String> configs = matcher.getConfigs();
            String actionsValue = configs.get("actions");
            assertTrue(actionsValue.contains("greet"));
            assertTrue(actionsValue.contains("farewell"));
        }

        @Test
        void clone_preservesActions() {
            var matcher = new ActionMatcher();
            matcher.setConfigs(Map.of("actions", "test_action"));
            var cloned = (ActionMatcher) matcher.clone();
            assertNotSame(matcher, cloned);
            assertEquals("test_action", cloned.getActions().get(0));
        }
    }

    // --- ContentTypeMatcher ---

    @Nested
    class ContentTypeMatcherTests {

        @Test
        void id() {
            assertEquals("contentTypeMatcher", new ContentTypeMatcher().getId());
        }

        @Test
        void defaultConfigs() {
            var matcher = new ContentTypeMatcher();
            var configs = matcher.getConfigs();
            assertEquals("1", configs.get("minCount"));
            assertFalse(configs.containsKey("mimeType"));
        }

        @Test
        void setConfigs_setsMimeTypeAndMinCount() {
            var matcher = new ContentTypeMatcher();
            matcher.setConfigs(Map.of("mimeType", "image/*", "minCount", "2"));
            var configs = matcher.getConfigs();
            assertEquals("image/*", configs.get("mimeType"));
            assertEquals("2", configs.get("minCount"));
        }

        @Test
        void setConfigs_invalidMinCount_defaults() {
            var matcher = new ContentTypeMatcher();
            matcher.setConfigs(Map.of("mimeType", "text/plain", "minCount", "notanumber"));
            assertEquals("1", matcher.getConfigs().get("minCount"));
        }

        @Test
        void setConfigs_negativeMinCount_clamped() {
            var matcher = new ContentTypeMatcher();
            matcher.setConfigs(Map.of("mimeType", "text/plain", "minCount", "-5"));
            assertEquals("1", matcher.getConfigs().get("minCount"));
        }

        @Test
        void setConfigs_null_noOp() {
            assertDoesNotThrow(() -> new ContentTypeMatcher().setConfigs(null));
        }

        @Test
        void execute_noMimeType_fails() {
            var matcher = new ContentTypeMatcher();
            var memory = mock(IConversationMemory.class);
            var step = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);

            assertEquals(ExecutionState.FAIL, matcher.execute(memory, new ArrayList<>()));
        }

        @Test
        void execute_noAttachments_fails() {
            var matcher = new ContentTypeMatcher();
            matcher.setConfigs(Map.of("mimeType", "image/*"));

            var memory = mock(IConversationMemory.class);
            var step = mock(IConversationMemory.IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(step);
            when(step.getLatestData(any(MemoryKey.class))).thenReturn(null);

            assertEquals(ExecutionState.FAIL, matcher.execute(memory, new ArrayList<>()));
        }

        @Test
        void clone_preservesConfigs() {
            var matcher = new ContentTypeMatcher();
            matcher.setConfigs(Map.of("mimeType", "application/pdf", "minCount", "3"));

            var cloned = (ContentTypeMatcher) matcher.clone();
            assertNotSame(matcher, cloned);
            assertEquals("application/pdf", cloned.getConfigs().get("mimeType"));
            assertEquals("3", cloned.getConfigs().get("minCount"));
        }
    }
}
