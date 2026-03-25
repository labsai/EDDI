package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.ConversationLogGenerator;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import dev.langchain4j.data.message.*;

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
 */
class ConversationHistoryBuilder {

    /**
     * Build the full list of ChatMessages for an LLM call.
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

        // Generate conversation history from memory
        var chatMessages = new ArrayList<>(new ConversationLogGenerator(memory).generate(logSizeLimit, includeFirstAgentMessage).getMessages()
                .stream().map(this::convertMessage).toList());

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
