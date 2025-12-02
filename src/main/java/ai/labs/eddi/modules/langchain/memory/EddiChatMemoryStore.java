package ai.labs.eddi.modules.langchain.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.ConversationLogGenerator;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * A quarkus-langchain4j ChatMemoryStore backed by EDDI's IConversationMemoryStore.
 * This ensures that AI agents and the EDDI lifecycle share the same stateful conversation history.
 *
 * This bridge converts EDDI's conversation outputs into langchain4j's ChatMessage format.
 */
@ApplicationScoped
public class EddiChatMemoryStore implements ChatMemoryStore {
    private static final Logger LOGGER = Logger.getLogger(EddiChatMemoryStore.class);

    @Inject
    IConversationMemoryStore conversationMemoryStore;

    /**
     * Loads the EDDI conversation history and converts it to Langchain4j's format.
     *
     * @param memoryId The conversation ID (as Object per langchain4j interface)
     * @return List of ChatMessages representing the conversation history
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String conversationId = (String) memoryId;
        List<ChatMessage> messages = new ArrayList<>();

        try {
            // Load the conversation snapshot from EDDI's storage
            ConversationMemorySnapshot snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);

            // Use EDDI's ConversationLogGenerator to get structured conversation history
            ConversationLog conversationLog = new ConversationLogGenerator(snapshot).generate();

            // Convert EDDI's ConversationLog to langchain4j's ChatMessage format
            for (ConversationLog.ConversationPart part : conversationLog.getMessages()) {
                String role = part.getRole();

                // Extract text content from the conversation part
                StringBuilder textContent = new StringBuilder();
                for (ConversationLog.ConversationPart.Content content : part.getContent()) {
                    if (content.getType() == ConversationLog.ConversationPart.ContentType.text) {
                        textContent.append(content.getValue());
                    }
                }

                String text = textContent.toString();
                if (text.isEmpty()) {
                    continue; // Skip empty messages
                }

                // Convert to appropriate langchain4j message type
                if ("user".equals(role)) {
                    messages.add(UserMessage.from(text));
                } else if ("assistant".equals(role)) {
                    messages.add(AiMessage.from(text));
                }
            }

            LOGGER.debug("Loaded " + messages.size() + " messages for conversation " + conversationId);

        } catch (IResourceStore.ResourceNotFoundException e) {
            // New conversation - return empty list
            LOGGER.debug("Conversation " + conversationId + " not found, starting new conversation");
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Error loading conversation " + conversationId, e);
        }

        return messages;
    }

    /**
     * Updates messages in EDDI's memory store.
     * Note: In EDDI's architecture, the conversation memory is managed by the lifecycle.
     * This method is called by langchain4j after agent execution, but EDDI persists
     * messages through the normal lifecycle flow, not through this method.
     *
     * We implement this as a no-op because EDDI's LangchainTask will handle
     * storing the final agent response in the conversation memory.
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // No-op: EDDI manages memory persistence through the lifecycle
        // The LangchainTask will store the agent's response in IConversationMemory
        LOGGER.trace("updateMessages called for conversation " + memoryId +
                     " (handled by EDDI lifecycle, not persisted here)");
    }

    /**
     * Deletes all messages for a conversation.
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String conversationId = (String) memoryId;
        try {
            conversationMemoryStore.deleteConversationMemorySnapshot(conversationId);
            LOGGER.info("Deleted conversation " + conversationId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.warn("Attempted to delete non-existent conversation " + conversationId);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Error deleting conversation " + conversationId, e);
        }
    }
}

