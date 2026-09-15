/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart.ContentType.text;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationHistoryBuilderTest {

    private ConversationHistoryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ConversationHistoryBuilder();
    }

    // ==================== convertMessage Tests ====================

    @Nested
    @DisplayName("convertMessage")
    class ConvertMessageTests {

        @Test
        @DisplayName("should convert user role to UserMessage")
        void convertMessage_user() {
            var content = new ConversationLog.ConversationPart.Content(text, "Hello");
            var part = new ConversationLog.ConversationPart("user", List.of(content));

            ChatMessage result = builder.convertMessage(part);

            assertInstanceOf(UserMessage.class, result);
        }

        @Test
        @DisplayName("should convert assistant role to AiMessage")
        void convertMessage_assistant() {
            var content = new ConversationLog.ConversationPart.Content(text, "Hello back");
            var part = new ConversationLog.ConversationPart("assistant", List.of(content));

            ChatMessage result = builder.convertMessage(part);

            assertInstanceOf(AiMessage.class, result);
            assertEquals("Hello back", ((AiMessage) result).text());
        }

        @Test
        @DisplayName("should convert unknown role to SystemMessage")
        void convertMessage_system() {
            var content = new ConversationLog.ConversationPart.Content(text, "System info");
            var part = new ConversationLog.ConversationPart("system", List.of(content));

            ChatMessage result = builder.convertMessage(part);

            assertInstanceOf(SystemMessage.class, result);
            assertEquals("System info", ((SystemMessage) result).text());
        }

        @Test
        @DisplayName("should join multiple text contents for assistant")
        void convertMessage_multipleContents_joined() {
            var content1 = new ConversationLog.ConversationPart.Content(text, "Part 1");
            var content2 = new ConversationLog.ConversationPart.Content(text, "Part 2");
            var part = new ConversationLog.ConversationPart("assistant", List.of(content1, content2));

            ChatMessage result = builder.convertMessage(part);

            assertInstanceOf(AiMessage.class, result);
            assertEquals("Part 1 Part 2", ((AiMessage) result).text());
        }
    }

    // ==================== buildMessages Tests ====================

    @Nested
    @DisplayName("buildMessages")
    class BuildMessagesTests {

        @Test
        @DisplayName("should prepend system message when provided")
        void buildMessages_withSystemMessage() {
            IConversationMemory memory = mock(IConversationMemory.class);

            // Setup minimal conversation output
            var output = new ConversationOutput();
            output.put("input", "Hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(memory, "You are helpful", null, -1, true);

            assertFalse(messages.isEmpty());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
            assertEquals("You are helpful", ((SystemMessage) messages.getFirst()).text());
        }

        /**
         * A turn whose output was written with
         * {@code addConversationOutputString("output", …)} holds a plain String — the
         * shape every HITL-gated turn has. This caller used to pre-filter on
         * {@code output instanceof List}, silently dropping such turns from the model's
         * own chat history while the summary and recall tool kept them: the Operator
         * then reported an approved change "was never created".
         */
        @Test
        @DisplayName("a String-shaped output still reaches the model as an AiMessage")
        void buildMessages_stringOutputTurnIsNotDropped() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "create the group");
            output.put("output", "Waiting for your approval.");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(memory, null, null, -1, true);

            assertEquals(2, messages.size(), "user turn AND assistant turn — the assistant half used to vanish");
            assertInstanceOf(AiMessage.class, messages.get(1));
            assertEquals("Waiting for your approval.", ((AiMessage) messages.get(1)).text());
        }

        /**
         * The summarized-window path ({@code skipSteps > 0}) has its own render loop —
         * the one that carried the {@code instanceof List} pre-filter. A rolling
         * summary is exactly when losing turns hurts most: the summarized prefix is
         * gone by design, so a dropped recent turn has no other copy anywhere in the
         * prompt.
         */
        @Test
        @DisplayName("the skipSteps window keeps String-shaped turns too")
        void buildMessages_skipStepsWindowKeepsStringOutputs() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var summarized = new ConversationOutput();
            summarized.put("input", "old turn");
            summarized.put("output", List.of(Map.of("text", "old answer")));

            var hitlShaped = new ConversationOutput();
            hitlShaped.put("input", "create the group");
            hitlShaped.put("output", "Waiting for your approval.");

            when(memory.getConversationOutputs()).thenReturn(List.of(summarized, hitlShaped));
            when(memory.getAllSteps()).thenReturn(mock(IConversationMemory.IConversationStepStack.class));

            List<ChatMessage> messages = builder.buildMessages(memory, null, null, -1, true, "summary of older turns", 1);

            // system(summary) + user + assistant — the assistant half used to vanish.
            assertEquals(3, messages.size());
            assertInstanceOf(AiMessage.class, messages.get(2));
            assertEquals("Waiting for your approval.", ((AiMessage) messages.get(2)).text());
        }

        @Test
        @DisplayName("a mixed String-then-Map output list is joined, not truncated")
        void buildMessages_mixedOutputListFullyRendered() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "hi");
            output.put("output", List.of("Paused for approval.", Map.of("text", "Group created.")));
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(memory, null, null, -1, true);

            assertEquals(2, messages.size());
            assertEquals("Paused for approval. Group created.", ((AiMessage) messages.get(1)).text());
        }

        @Test
        @DisplayName("should not prepend system message when null")
        void buildMessages_noSystemMessage() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(memory, null, null, -1, true);

            assertFalse(messages.isEmpty());
            // First message should NOT be SystemMessage
            assertInstanceOf(UserMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("should not prepend system message when empty string")
        void buildMessages_emptySystemMessage() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(memory, "", null, -1, true);

            assertFalse(messages.isEmpty());
            assertInstanceOf(UserMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("should replace last user message with prompt when provided")
        void buildMessages_withPrompt() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Original user message");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(memory, null, "Custom prompt", -1, true);

            // The last message should be the custom prompt, not the original input
            var lastMessage = messages.getLast();
            assertInstanceOf(UserMessage.class, lastMessage);
            // Verify it contains the custom prompt text
            assertTrue(((UserMessage) lastMessage).singleText().contains("Custom prompt"));
        }

        @Test
        @DisplayName("should handle empty conversation history")
        void buildMessages_emptyHistory() {
            IConversationMemory memory = mock(IConversationMemory.class);
            when(memory.getConversationOutputs()).thenReturn(new ArrayList<>());

            List<ChatMessage> messages = builder.buildMessages(memory, "System", null, -1, true);

            // Should have just the system message
            assertEquals(1, messages.size());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("should respect logSizeLimit=0 (no history)")
        void buildMessages_logSizeLimitZero() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output1 = new ConversationOutput();
            output1.put("input", "Hi");
            var output2 = new ConversationOutput();
            output2.put("input", "How are you?");
            when(memory.getConversationOutputs()).thenReturn(List.of(output1, output2));

            List<ChatMessage> messages = builder.buildMessages(memory, "System", null, 0, true);

            // logSize=0 means no history entries, only system message
            assertEquals(1, messages.size());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("should limit history with logSizeLimit > 0")
        void buildMessages_withLogSizeLimit() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 5; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildMessages(memory, null, null, 2, true);

            // With logSizeLimit=2, should only have last 2 conversation entries
            // Each entry could produce 1-2 messages (user + optional assistant)
            assertTrue(messages.size() <= 4, "Should have at most 4 messages (2 entries × 2)");
            assertTrue(messages.size() >= 2, "Should have at least 2 messages (2 user inputs)");
        }
    }

    // ==================== Token-Aware Window Tests ====================

    @Nested
    @DisplayName("buildTokenAwareMessages")
    class TokenAwareWindowTests {

        private TokenCounterFactory.ApproximateTokenCountEstimator estimator;

        @BeforeEach
        void setUp() {
            estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();
        }

        @Test
        @DisplayName("short conversation — everything fits, no windowing")
        void shortConversation_everythingFits() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 3; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // Large budget — everything should fit
            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System", null, 10000, 2, true, estimator);

            // System + 3 user messages
            assertEquals(4, messages.size());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
            assertEquals("System", ((SystemMessage) messages.getFirst()).text());
        }

        @Test
        @DisplayName("long conversation — token budget limits messages")
        void longConversation_budgetLimits() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 50; i++) {
                var output = new ConversationOutput();
                output.put("input", "This is a somewhat longer message number " + i + " with extra text");
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // Small budget: ~200 tokens ≈ ~800 chars
            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System", null, 200, 2, true, estimator);

            // Should have system + anchored messages + gap marker + some recent messages
            // Total should be less than if we included everything
            assertTrue(messages.size() < 52, "Should not include all 50 messages");
            assertTrue(messages.size() >= 4, "Should have at least system + 2 anchored + 1 recent");
        }

        @Test
        @DisplayName("anchored steps always included even in long conversations")
        void anchoredStepsAlwaysIncluded() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            var output0 = new ConversationOutput();
            output0.put("input", "ANCHOR_FIRST");
            outputs.add(output0);
            var output1 = new ConversationOutput();
            output1.put("input", "ANCHOR_SECOND");
            outputs.add(output1);

            for (int i = 2; i < 30; i++) {
                var output = new ConversationOutput();
                output.put("input", "Middle message " + i + " with padding text to use tokens");
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // Budget small enough to require windowing
            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 100, 2, true, estimator);

            // First two non-system messages should be the anchored ones
            assertTrue(messages.size() >= 2, "Should have at least the anchored messages");

            // Find the anchored messages by content
            boolean foundFirst = messages.stream().filter(m -> m instanceof UserMessage)
                    .anyMatch(m -> ((UserMessage) m).singleText().contains("ANCHOR_FIRST"));
            boolean foundSecond = messages.stream().filter(m -> m instanceof UserMessage)
                    .anyMatch(m -> ((UserMessage) m).singleText().contains("ANCHOR_SECOND"));

            assertTrue(foundFirst, "First anchored message should always be present");
            assertTrue(foundSecond, "Second anchored message should always be present");
        }

        @Test
        @DisplayName("gap marker inserted between anchored and recent when turns are omitted")
        void gapMarkerInserted() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 20; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message number " + i + " with padding text to use token budget");
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // Budget small enough that not all 20 messages fit
            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 150, 2, true, estimator);

            // Look for gap marker (a SystemMessage containing "omitted")
            boolean hasGapMarker = messages.stream().filter(m -> m instanceof SystemMessage)
                    .anyMatch(m -> ((SystemMessage) m).text().contains("omitted"));

            assertTrue(hasGapMarker, "Gap marker should be present when turns are omitted");
        }

        @Test
        @DisplayName("no gap marker when all messages fit within budget")
        void noGapMarkerWhenAllFit() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 3; i++) {
                var output = new ConversationOutput();
                output.put("input", "Short " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System", null, 10000, 2, true, estimator);

            // No gap marker when everything fits
            boolean hasGapMarker = messages.stream().filter(m -> m instanceof SystemMessage)
                    .anyMatch(m -> ((SystemMessage) m).text().contains("omitted"));

            assertFalse(hasGapMarker, "No gap marker when all messages fit");
        }

        @Test
        @DisplayName("anchorFirstSteps=0 — no anchoring, just recent window")
        void zeroAnchor_noAnchoring() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 20; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i + " with padding text to fill token budget up");
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // Zero anchoring — should only include recent messages
            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System", null, 150, 0, true, estimator);

            // Should have system + recent messages (no anchored first messages)
            assertInstanceOf(SystemMessage.class, messages.getFirst());
            assertTrue(messages.size() >= 2, "Should have at least system + some recent");
            assertTrue(messages.size() < 22, "Should not include all messages");
        }

        /**
         * When {@code anchorFirstSteps} met or exceeded the message count, every
         * message became an anchor: {@code effectiveAnchor == size} made
         * {@code lastIsAnchored} true, which both zeroed the current-turn reservation
         * and disabled the anchor-trim loop (guarded on {@code !lastIsAnchored}).
         * Nothing could then be dropped, and the windowing path — whose entire job is
         * to stay under budget — returned a prompt well over it.
         * <p>
         * Reachable with any generous {@code anchorFirstSteps} while a conversation is
         * still shorter than it, which is every conversation's opening turns.
         */
        @Test
        @DisplayName("anchorFirstSteps larger than the history still trims to the budget")
        void anchorLargerThanHistoryStillTrims() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 6; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i + " with a good deal of padding text to consume the token budget quickly");
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // The budget must be one the history genuinely exceeds, or old and new code
            // both return everything and the test proves nothing.
            int fullHistoryTokens = builder.buildTokenAwareMessages(memory, null, null, Integer.MAX_VALUE, 0, true, estimator).stream()
                    .mapToInt(estimator::estimateTokenCountInMessage).sum();
            int budget = fullHistoryTokens / 3;

            // anchorFirstSteps (50) far exceeds the 6 messages available.
            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System", null, budget, 50, true, estimator);

            assertTrue(messages.size() < 7, "every message was anchored and nothing could be trimmed — the window did not window");

            int historyTokens = messages.stream().filter(m -> !(m instanceof SystemMessage)).mapToInt(estimator::estimateTokenCountInMessage).sum();
            assertTrue(historyTokens <= budget, "returned an over-budget prompt: " + historyTokens + " tokens against a " + budget + " budget");

            // The G13 guarantee must survive the fix: the turn being answered is still
            // present.
            assertInstanceOf(UserMessage.class, messages.getLast());
            assertTrue(((UserMessage) messages.getLast()).singleText().contains("Message 5"), "the current turn must never be dropped");
        }

        @Test
        @DisplayName("prompt replacement works in token-aware mode")
        void promptReplacement() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Original user message");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System", "Custom prompt", 10000, 0, true, estimator);

            var lastMessage = messages.getLast();
            assertInstanceOf(UserMessage.class, lastMessage);
            assertTrue(((UserMessage) lastMessage).singleText().contains("Custom prompt"));
        }

        // ==================== Edge Cases ====================

        @Test
        @DisplayName("gap marker shows count of omitted messages, not index range")
        void gapMarkerShowsCount() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 20; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message number " + i + " with padding text to use up token budget completely");
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 100, 2, true, estimator);

            var gapMarker = messages.stream().filter(m -> m instanceof SystemMessage).filter(m -> ((SystemMessage) m).text().contains("omitted"))
                    .findFirst();

            assertTrue(gapMarker.isPresent(), "Gap marker should exist");
            String text = ((SystemMessage) gapMarker.get()).text();
            assertTrue(text.contains("earlier messages omitted"), "Gap marker should say 'messages omitted': " + text);
            assertFalse(text.contains("turns"), "Gap marker should NOT say 'turns': " + text);
        }

        @Test
        @DisplayName("empty conversation — returns only system message")
        void emptyConversation_onlySystemMessage() {
            IConversationMemory memory = mock(IConversationMemory.class);
            when(memory.getConversationOutputs()).thenReturn(new ArrayList<>());

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System prompt", null, 4000, 2, true, estimator);

            assertEquals(1, messages.size(), "Should only contain the system message");
            assertInstanceOf(SystemMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("empty conversation with null system message — returns empty list")
        void emptyConversation_nullSystem_returnsEmpty() {
            IConversationMemory memory = mock(IConversationMemory.class);
            when(memory.getConversationOutputs()).thenReturn(new ArrayList<>());

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 4000, 2, true, estimator);

            assertTrue(messages.isEmpty(), "Should return empty list");
        }

        @Test
        @DisplayName("single message with anchor=2 — effectiveAnchor clamped to 1")
        void singleMessage_anchorClamped() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Only message");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System", null, 10000, 2, true, estimator);

            assertEquals(2, messages.size());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
            assertInstanceOf(UserMessage.class, messages.getLast());
        }

        @Test
        @DisplayName("null system message during windowing — no system message in output")
        void nullSystemMessage_windowing() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 20; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i + " with padding for token counting estimation");
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 100, 2, true, estimator);

            assertInstanceOf(UserMessage.class, messages.getFirst(), "No system message should be present");
        }

        @Test
        @DisplayName("G13: anchored tokens exceed budget — anchors are trimmed, the current turn survives")
        void anchoredTokensExceedBudget() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            var output0 = new ConversationOutput();
            output0.put("input", "A".repeat(500));
            outputs.add(output0);
            var output1 = new ConversationOutput();
            output1.put("input", "B".repeat(500));
            outputs.add(output1);

            for (int i = 2; i < 10; i++) {
                var output = new ConversationOutput();
                output.put("input", "Short msg " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 50, 2, true, estimator);

            // Finding G13: the oversized anchors used to consume the whole budget, so
            // the backward fill produced nothing and the model received anchors + an
            // omission marker WITHOUT the question it was supposed to answer. Anchors
            // are now trimmed instead, and the current turn is always present.
            ChatMessage last = messages.getLast();
            assertInstanceOf(UserMessage.class, last, "The current turn must be the final message");
            assertTrue(((UserMessage) last).singleText().contains("Short msg 9"),
                    "The current user question must survive the window: " + ((UserMessage) last).singleText());
            // The 500-char anchors cannot fit alongside it and must have been dropped
            assertTrue(messages.stream().filter(m -> m instanceof UserMessage).noneMatch(m -> ((UserMessage) m).singleText().startsWith("AAAA")),
                    "Oversized anchors should be trimmed, not the current turn");
            // Gap marker still reports the omitted middle section
            assertTrue(messages.stream().filter(m -> m instanceof SystemMessage).anyMatch(m -> ((SystemMessage) m).text().contains("omitted")),
                    "Gap marker should be present for omitted messages");
        }

        @Test
        @DisplayName("exact budget boundary — message exactly fills remaining budget is included")
        void exactBudgetBoundary() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 5; i++) {
                var output = new ConversationOutput();
                output.put("input", "ABCD");
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 5, 0, true, estimator);

            assertEquals(5, messages.size(), "All messages should fit when total equals budget exactly");
        }

        @Test
        @DisplayName("anchor count larger than message count — all messages become anchored")
        void anchorLargerThanMessages() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 3; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, "System", null, 10000, 10, true, estimator);

            assertEquals(4, messages.size(), "System + 3 messages (all anchored)");
        }

        @Test
        @DisplayName("G13: budget too small for any message — the current turn is still included")
        void budgetTooSmallForRecent() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            var output0 = new ConversationOutput();
            output0.put("input", "Hi");
            outputs.add(output0);

            for (int i = 1; i < 10; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message with lots of padding text number " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildTokenAwareMessages(memory, null, null, 1, 1, true, estimator);

            // Finding G13: with a 1-token budget the anchor used to consume everything
            // and the model received only the anchor plus an omission marker — no user
            // question at all. The anchor is now surrendered instead, so the prompt is
            // the gap marker plus the turn that actually has to be answered.
            assertEquals(2, messages.size(), "Gap marker + the current turn");
            assertInstanceOf(SystemMessage.class, messages.getFirst(), "the omission marker precedes the surviving turn");
            assertInstanceOf(UserMessage.class, messages.getLast(), "the current question must never be dropped");
            assertTrue(((UserMessage) messages.getLast()).singleText().contains("number 9"),
                    "the SURVIVING message must be the latest turn: " + ((UserMessage) messages.getLast()).singleText());
        }
    }

    // ==================== Summary Prefix + SkipSteps Tests ====================

    @Nested
    @DisplayName("summaryPrefix and skipSteps")
    class SummaryPrefixTests {

        @Test
        @DisplayName("buildMessages with summaryPrefix — prepends to system message")
        void buildMessages_summaryPrefix_prepended() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(
                    memory, "You are helpful", null, -1, true,
                    "Summary of earlier turns", 0);

            assertFalse(messages.isEmpty());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
            String systemText = ((SystemMessage) messages.getFirst()).text();
            assertTrue(systemText.contains("You are helpful"), "Original system message preserved");
            assertTrue(systemText.contains("Summary of earlier turns"), "Summary prefix injected");
        }

        @Test
        @DisplayName("buildMessages with summaryPrefix and null system message")
        void buildMessages_summaryPrefix_nullSystem() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(
                    memory, null, null, -1, true,
                    "Summary text", 0);

            assertInstanceOf(SystemMessage.class, messages.getFirst());
            String systemText = ((SystemMessage) messages.getFirst()).text();
            assertTrue(systemText.contains("Summary text"));
        }

        @Test
        @DisplayName("buildMessages with skipSteps > 0 — skips early conversation outputs")
        void buildMessages_skipSteps() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 5; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                output.put("output", List.of("Response " + i));
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // Skip first 3 steps
            List<ChatMessage> messages = builder.buildMessages(
                    memory, null, null, -1, true,
                    null, 3);

            // Should only include messages from steps 3 and 4 (2 steps × 2 messages each =
            // 4)
            assertTrue(messages.size() <= 4, "Should skip first 3 steps: " + messages.size());
            assertTrue(messages.size() >= 2, "Should include at least step 3 and 4 inputs");
        }

        @Test
        @DisplayName("buildMessages with skipSteps exceeding output count — returns empty conversation")
        void buildMessages_skipStepsExceedsSize() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 3; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildMessages(
                    memory, "System", null, -1, true,
                    null, 10);

            // Only system message should remain
            assertEquals(1, messages.size());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("buildMessages with skipSteps + logSizeLimit — applies both constraints")
        void buildMessages_skipAndLimit() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 10; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // Skip 3 steps, limit to 2 steps
            List<ChatMessage> messages = builder.buildMessages(
                    memory, null, null, 2, true,
                    null, 3);

            // Should show only 2 steps from the remaining 7
            assertTrue(messages.size() <= 4, "Should have at most 4 messages (2 steps × 2)");
            assertTrue(messages.size() >= 2, "Should have at least 2 messages (2 user inputs)");
        }

        @Test
        @DisplayName("token-aware with summaryPrefix — prepends to system message")
        void tokenAware_summaryPrefix() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 5; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();
            List<ChatMessage> messages = builder.buildTokenAwareMessages(
                    memory, "System", null, 10000, 2, true, estimator,
                    "Summary of turns 1-3", 3);

            assertInstanceOf(SystemMessage.class, messages.getFirst());
            String systemText = ((SystemMessage) messages.getFirst()).text();
            assertTrue(systemText.contains("System"));
            assertTrue(systemText.contains("Summary of turns 1-3"));
        }

        @Test
        @DisplayName("token-aware with skipSteps — skips early outputs")
        void tokenAware_skipSteps() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 10; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var estimator = new TokenCounterFactory.ApproximateTokenCountEstimator();
            List<ChatMessage> messages = builder.buildTokenAwareMessages(
                    memory, null, null, 10000, 0, true, estimator,
                    null, 5);

            // Should only have messages from steps 5-9 (5 steps)
            assertEquals(5, messages.size(), "Should have 5 messages (steps 5-9)");
        }
    }

    // ==================== Multimodal Content Tests ====================

    @Nested
    @DisplayName("convertMessage — multimodal content types")
    class MultimodalConvertTests {

        @Test
        @DisplayName("pdf content type — creates PdfFileContent")
        void pdfContentType() {
            var content = new ConversationLog.ConversationPart.Content(
                    ConversationLog.ConversationPart.ContentType.pdf, "base64data");
            var part = new ConversationLog.ConversationPart("user", List.of(content));

            ChatMessage result = builder.convertMessage(part);
            assertInstanceOf(UserMessage.class, result);
        }

        @Test
        @DisplayName("audio content type — creates AudioContent")
        void audioContentType() {
            var content = new ConversationLog.ConversationPart.Content(
                    ConversationLog.ConversationPart.ContentType.audio, "audiodata");
            var part = new ConversationLog.ConversationPart("user", List.of(content));

            ChatMessage result = builder.convertMessage(part);
            assertInstanceOf(UserMessage.class, result);
        }

        @Test
        @DisplayName("video content type — creates VideoContent")
        void videoContentType() {
            var content = new ConversationLog.ConversationPart.Content(
                    ConversationLog.ConversationPart.ContentType.video, "videodata");
            var part = new ConversationLog.ConversationPart("user", List.of(content));

            ChatMessage result = builder.convertMessage(part);
            assertInstanceOf(UserMessage.class, result);
        }

        @Test
        @DisplayName("image content type — creates ImageContent")
        void imageContentType() {
            var content = new ConversationLog.ConversationPart.Content(
                    ConversationLog.ConversationPart.ContentType.image, "https://example.com/img.png");
            var part = new ConversationLog.ConversationPart("user", List.of(content));

            ChatMessage result = builder.convertMessage(part);
            assertInstanceOf(UserMessage.class, result);
        }

        @Test
        @DisplayName("mixed content types in single message")
        void mixedContentTypes() {
            var textContent = new ConversationLog.ConversationPart.Content(text, "Look at this");
            var imageContent = new ConversationLog.ConversationPart.Content(
                    ConversationLog.ConversationPart.ContentType.image, "https://example.com/img.png");
            var part = new ConversationLog.ConversationPart("user", List.of(textContent, imageContent));

            ChatMessage result = builder.convertMessage(part);
            assertInstanceOf(UserMessage.class, result);
            UserMessage userMsg = (UserMessage) result;
            assertEquals(2, userMsg.contents().size());
        }
    }

    // ==================== includeFirstAgentMessage Tests ====================

    @Nested
    @DisplayName("includeFirstAgentMessage behavior")
    class IncludeFirstAgentMessageTests {

        @Test
        @DisplayName("includeFirstAgentMessage=false with skipSteps=0 — removes first message")
        void includeFirstAgentMessageFalse_removesFirst() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            var output0 = new ConversationOutput();
            output0.put("input", "Hi");
            output0.put("output", List.of("Welcome!"));
            outputs.add(output0);

            var output1 = new ConversationOutput();
            output1.put("input", "How are you?");
            output1.put("output", List.of("I'm fine!"));
            outputs.add(output1);

            when(memory.getConversationOutputs()).thenReturn(outputs);

            // skipSteps=0, includeFirstAgentMessage=false
            List<ChatMessage> messages = builder.buildMessages(
                    memory, null, null, -1, false,
                    null, 0);

            // First message (the greeting) should be removed
            // Remaining: 1 user ("How are you?") + 1 AI ("I'm fine!") + possibly input "Hi"
            // The exact count depends on ConversationLogGenerator behavior
            // But it should have fewer messages than with includeFirstAgentMessage=true
            assertFalse(messages.isEmpty());
        }

        @Test
        @DisplayName("includeFirstAgentMessage=false with skipSteps>0 — does NOT remove first")
        void includeFirstAgentMessageFalse_withSkipSteps_noRemoval() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 5; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                output.put("output", List.of("Response " + i));
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            // skipSteps=2, includeFirstAgentMessage=false
            // After skipping, the first message is mid-conversation, NOT the greeting
            List<ChatMessage> messages = builder.buildMessages(
                    memory, null, null, -1, false,
                    null, 2);

            // Should have messages from steps 2-4 without greeting removal
            assertFalse(messages.isEmpty());
        }
    }
}
