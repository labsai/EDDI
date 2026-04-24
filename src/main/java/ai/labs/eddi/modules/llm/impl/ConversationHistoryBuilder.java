/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.ConversationLogGenerator;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.TokenCountEstimator;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Converts EDDI conversation memory into a langchain4j ChatMessage list.
 * <p>
 * Handles system message prepending, prompt replacement, log size limits, and
 * multi-modal content types (text, pdf, audio, video).
 * <p>
 * Supports two windowing modes:
 * <ul>
 * <li><strong>Step-count</strong> (legacy): include last N conversation steps
 * verbatim</li>
 * <li><strong>Token-aware</strong> (Strategy 1): pack messages into a token
 * budget with anchored opening steps</li>
 * </ul>
 */
class ConversationHistoryBuilder {

    private static final Logger LOGGER = Logger.getLogger(ConversationHistoryBuilder.class);

    /**
     * Build the full list of ChatMessages for an LLM call (step-count mode).
     *
     * @param memory
     *            conversation memory to read history from
     * @param systemMessage
     *            system message (may be null/empty)
     * @param prompt
     *            optional prompt to replace last user message (may be null/empty)
     * @param logSizeLimit
     *            conversation history limit (-1 = unlimited)
     * @param includeFirstAgentMessage
     *            whether to include the initial Agent greeting
     * @return ordered list of ChatMessages (system → history → user/prompt)
     */
    List<ChatMessage> buildMessages(IConversationMemory memory, String systemMessage, String prompt, int logSizeLimit,
                                    boolean includeFirstAgentMessage) {
        return buildMessages(memory, systemMessage, prompt, logSizeLimit, includeFirstAgentMessage, null, 0);
    }

    /**
     * Build the full list of ChatMessages with optional summary injection
     * (step-count mode).
     *
     * @param memory
     *            conversation memory to read history from
     * @param systemMessage
     *            system message (may be null/empty)
     * @param prompt
     *            optional prompt to replace last user message (may be null/empty)
     * @param logSizeLimit
     *            conversation history limit (-1 = unlimited)
     * @param includeFirstAgentMessage
     *            whether to include the initial Agent greeting
     * @param summaryPrefix
     *            rolling summary text to prepend to system message (may be null)
     * @param skipSteps
     *            number of earliest conversation outputs to skip (already
     *            summarized)
     * @return ordered list of ChatMessages (system → history → user/prompt)
     */
    List<ChatMessage> buildMessages(IConversationMemory memory, String systemMessage, String prompt, int logSizeLimit,
                                    boolean includeFirstAgentMessage, String summaryPrefix, int skipSteps) {

        // Prepend summary to system message if present
        if (!isNullOrEmpty(summaryPrefix)) {
            systemMessage = (isNullOrEmpty(systemMessage) ? "" : systemMessage + "\n\n") + summaryPrefix;
        }

        // Generate conversation history from memory, applying skipSteps
        // When skipSteps > 0, we generate a custom log starting from the skip boundary
        List<ChatMessage> chatMessages;
        if (skipSteps > 0) {
            chatMessages = generateMessagesFromOutputs(memory, skipSteps, logSizeLimit, includeFirstAgentMessage);
        } else {
            chatMessages = new ArrayList<>(new ConversationLogGenerator(memory).generate(logSizeLimit, includeFirstAgentMessage).getMessages()
                    .stream().map(this::convertMessage).toList());
        }

        // If a custom prompt is defined, replace the last user input with it
        if (!isNullOrEmpty(prompt)) {
            if (!chatMessages.isEmpty()) {
                chatMessages.removeLast();
            }
            chatMessages.add(UserMessage.from(prompt));
        }

        // Assemble full message list: system + history
        var messages = new LinkedList<ChatMessage>();
        if (!isNullOrEmpty(systemMessage)) {
            messages.add(new SystemMessage(systemMessage));
        }
        messages.addAll(chatMessages);

        return messages;
    }

    /**
     * Build the full list of ChatMessages with token-aware windowing.
     * <p>
     * Algorithm:
     * <ol>
     * <li>Generate ALL conversation messages (no step-count limit)</li>
     * <li>Reserve token budget for anchored steps (first N)</li>
     * <li>Fill remaining budget from most recent steps backward</li>
     * <li>If a gap exists between anchored and recent, insert a marker</li>
     * <li>Assemble: system + anchored + gap marker + recent + current</li>
     * </ol>
     * <p>
     * The gap marker text is:
     * {@code [... turns X-Y omitted from context — the full conversation is
     * preserved and can be recalled if needed ...]}
     *
     * @param memory
     *            conversation memory to read history from
     * @param systemMessage
     *            system message (may be null/empty)
     * @param prompt
     *            optional prompt to replace last user message (may be null/empty)
     * @param maxContextTokens
     *            maximum token budget for conversation history (excluding system
     *            prompt)
     * @param anchorFirstSteps
     *            number of opening conversation steps to always include
     * @param includeFirstAgentMessage
     *            whether to include the initial Agent greeting
     * @param estimator
     *            token count estimator to use for counting tokens
     * @return ordered list of ChatMessages fitting within the token budget
     */
    List<ChatMessage> buildTokenAwareMessages(IConversationMemory memory, String systemMessage, String prompt, int maxContextTokens,
                                              int anchorFirstSteps, boolean includeFirstAgentMessage, TokenCountEstimator estimator) {
        return buildTokenAwareMessages(memory, systemMessage, prompt, maxContextTokens, anchorFirstSteps, includeFirstAgentMessage, estimator, null,
                0);
    }

    /**
     * Build the full list of ChatMessages with token-aware windowing and optional
     * summary injection.
     *
     * @param memory
     *            conversation memory to read history from
     * @param systemMessage
     *            system message (may be null/empty)
     * @param prompt
     *            optional prompt to replace last user message (may be null/empty)
     * @param maxContextTokens
     *            maximum token budget for conversation history (excluding system
     *            prompt)
     * @param anchorFirstSteps
     *            number of opening conversation steps to always include
     * @param includeFirstAgentMessage
     *            whether to include the initial Agent greeting
     * @param estimator
     *            token count estimator to use for counting tokens
     * @param summaryPrefix
     *            rolling summary text to prepend to system message (may be null)
     * @param skipSteps
     *            number of earliest conversation outputs to skip (already
     *            summarized)
     * @return ordered list of ChatMessages fitting within the token budget
     */
    List<ChatMessage> buildTokenAwareMessages(IConversationMemory memory, String systemMessage, String prompt, int maxContextTokens,
                                              int anchorFirstSteps, boolean includeFirstAgentMessage, TokenCountEstimator estimator,
                                              String summaryPrefix, int skipSteps) {

        // Prepend summary to system message if present
        if (!isNullOrEmpty(summaryPrefix)) {
            systemMessage = (isNullOrEmpty(systemMessage) ? "" : systemMessage + "\n\n") + summaryPrefix;
        }

        // Generate conversation messages, skipping already-summarized turns
        List<ChatMessage> allMessages;
        if (skipSteps > 0) {
            allMessages = generateMessagesFromOutputs(memory, skipSteps, -1, includeFirstAgentMessage);
        } else {
            allMessages = new ArrayList<>(new ConversationLogGenerator(memory).generate(-1, includeFirstAgentMessage).getMessages().stream()
                    .map(this::convertMessage).toList());
        }

        // If a custom prompt is defined, replace the last user input with it
        if (!isNullOrEmpty(prompt)) {
            if (!allMessages.isEmpty()) {
                allMessages.removeLast();
            }
            allMessages.add(UserMessage.from(prompt));
        }

        // If conversation is short enough, try to fit everything
        int totalTokens = estimateTokens(estimator, allMessages);
        if (totalTokens <= maxContextTokens) {
            // Everything fits — no windowing needed
            var messages = new LinkedList<ChatMessage>();
            if (!isNullOrEmpty(systemMessage)) {
                messages.add(new SystemMessage(systemMessage));
            }
            messages.addAll(allMessages);
            return messages;
        }

        // === Token-aware windowing with anchored opening ===

        // Step 1: Reserve budget for anchored steps
        int effectiveAnchor = Math.min(anchorFirstSteps, allMessages.size());
        var anchoredMessages = new ArrayList<ChatMessage>();
        int anchoredTokens = 0;

        for (int i = 0; i < effectiveAnchor; i++) {
            ChatMessage msg = allMessages.get(i);
            int msgTokens = estimator.estimateTokenCountInMessage(msg);
            anchoredTokens += msgTokens;
            anchoredMessages.add(msg);
        }

        // Warn if anchored messages alone exceed the token budget
        if (anchoredTokens > maxContextTokens) {
            LOGGER.warnf(
                    "Anchored steps (%d) consume %d tokens, exceeding maxContextTokens=%d. "
                            + "Consider reducing anchorFirstSteps or increasing maxContextTokens.",
                    effectiveAnchor, anchoredTokens, maxContextTokens);
        }

        // Step 2: Fill remaining budget from most recent steps backward
        int remainingBudget = Math.max(0, maxContextTokens - anchoredTokens);
        var recentMessages = new ArrayList<ChatMessage>();
        int recentTokens = 0;
        int recentStartIndex = allMessages.size(); // exclusive — will be decremented

        for (int i = allMessages.size() - 1; i >= effectiveAnchor; i--) {
            ChatMessage msg = allMessages.get(i);
            int msgTokens = estimator.estimateTokenCountInMessage(msg);
            if (recentTokens + msgTokens > remainingBudget) {
                break; // budget exhausted
            }
            recentTokens += msgTokens;
            recentMessages.addFirst(msg);
            recentStartIndex = i;
        }

        // Step 3: Assemble result
        var messages = new LinkedList<ChatMessage>();
        if (!isNullOrEmpty(systemMessage)) {
            messages.add(new SystemMessage(systemMessage));
        }

        messages.addAll(anchoredMessages);

        // Insert gap marker if there are omitted messages between anchor and recent
        if (recentStartIndex > effectiveAnchor) {
            int omittedCount = recentStartIndex - effectiveAnchor;
            String gapMarker = String.format(
                    "[... %d earlier messages omitted from context — the full conversation is preserved and can be recalled if needed ...]",
                    omittedCount);
            messages.add(new SystemMessage(gapMarker));
        }

        messages.addAll(recentMessages);

        return messages;
    }

    /**
     * Estimate total token count for a list of messages.
     */
    private int estimateTokens(TokenCountEstimator estimator, List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimator.estimateTokenCountInMessage(msg);
        }
        return total;
    }

    /**
     * Generate ChatMessages from conversation outputs, starting from a given step.
     * Used when the rolling summary has consumed earlier turns and we only need the
     * recent unsummarized section.
     *
     * @param memory
     *            the conversation memory
     * @param skipSteps
     *            number of earliest conversation outputs to skip
     * @param logSizeLimit
     *            maximum number of steps to include (-1 = unlimited)
     * @param includeFirstAgentMessage
     *            whether to include an initial agent message (only relevant when
     *            skipSteps == 0)
     * @return mutable list of ChatMessages from the non-skipped section
     */
    private ArrayList<ChatMessage> generateMessagesFromOutputs(IConversationMemory memory, int skipSteps, int logSizeLimit,
                                                               boolean includeFirstAgentMessage) {

        var outputs = memory.getConversationOutputs();
        int startIndex = Math.min(skipSteps, outputs.size());

        // Apply logSize limit to the remaining (non-skipped) window
        if (logSizeLimit > 0) {
            int windowStart = outputs.size() > (startIndex + logSizeLimit) ? outputs.size() - logSizeLimit : startIndex;
            startIndex = Math.max(startIndex, windowStart);
        }

        var result = new ArrayList<ChatMessage>();
        for (int i = startIndex; i < outputs.size(); i++) {
            var output = outputs.get(i);
            var input = output.get("input", String.class);
            if (input != null) {
                result.add(UserMessage.from(input));
            }

            Object outputObj = output.get("output");
            if (outputObj instanceof List<?> outputList && !outputList.isEmpty()) {
                String text = ConversationOutputUtils.extractOutputText(output);
                if (text != null && !text.isEmpty()) {
                    result.add(AiMessage.from(text));
                }
            }
        }

        // When at conversation start (skipSteps == 0) and includeFirstAgentMessage is
        // false,
        // drop the opening agent greeting. This does NOT apply when skipSteps > 0
        // because
        // after step-skipping the first message is mid-conversation, not the opening
        // greeting.
        if (skipSteps == 0 && !includeFirstAgentMessage && !result.isEmpty()) {
            result.removeFirst();
        }

        return result;
    }

    /**
     * Convert an EDDI ConversationPart into a langchain4j ChatMessage. Supports
     * multi-modal content (text, pdf, audio, video) for user messages.
     */
    ChatMessage convertMessage(ConversationLog.ConversationPart eddiMessage) {
        return switch (eddiMessage.getRole().toLowerCase()) {
            case "user" -> {
                var contentList = new LinkedList<Content>();
                for (var content : eddiMessage.getContent()) {
                    switch (content.getType()) {
                        case text -> contentList.add(TextContent.from(content.getValue()));
                        case pdf -> contentList.add(PdfFileContent.from(content.getValue()));
                        case audio -> contentList.add(AudioContent.from(content.getValue()));
                        case video -> contentList.add(VideoContent.from(content.getValue()));
                        case image -> contentList.add(ImageContent.from(content.getValue()));
                        default -> {
                        }
                    }
                }
                yield UserMessage.from(contentList);
            }
            case "assistant" -> AiMessage.from(joinMessages(eddiMessage));
            default -> SystemMessage.from(joinMessages(eddiMessage));
        };
    }

    private static String joinMessages(ConversationLog.ConversationPart eddiMessage) {
        return eddiMessage.getContent().stream().map(ConversationLog.ConversationPart.Content::getValue).collect(Collectors.joining(" "));
    }
}
