package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.*;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles incoming A2A JSON-RPC requests by bridging them to EDDI's
 * {@link IConversationService}. Each A2A task maps to a conversation.
 *
 * @author ginccc
 */
@ApplicationScoped
public class A2ATaskHandler {

    private static final Logger LOGGER = Logger.getLogger(A2ATaskHandler.class);
    private static final String CACHE_NAME = "a2aTaskMapping";
    private static final int TASK_TIMEOUT_SECONDS = 60;

    private final IConversationService conversationService;

    /**
     * Maps A2A taskId → conversationId for multi-turn conversations. A task that
     * uses the same contextId should reuse the same conversation.
     */
    private final ICache<String, String> taskConversationCache;

    /**
     * Maps A2A contextId → conversationId so multi-turn requests on the same
     * context reuse the same conversation.
     */
    private final ICache<String, String> contextConversationCache;

    @Inject
    public A2ATaskHandler(IConversationService conversationService, ICacheFactory cacheFactory) {
        this.conversationService = conversationService;
        this.taskConversationCache = cacheFactory.getCache(CACHE_NAME);
        this.contextConversationCache = cacheFactory.getCache(CACHE_NAME + ":context");
    }

    /**
     * Handle a {@code tasks/send} request — the core A2A operation.
     */
    public A2ATask handleTaskSend(String agentId, Map<String, Object> params) throws Exception {
        // Extract message from params
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) params.get("message");
        if (message == null) {
            throw new IllegalArgumentException("Missing 'message' in params");
        }

        String taskId = params.containsKey("id") ? params.get("id").toString() : UUID.randomUUID().toString();
        String contextId = params.containsKey("contextId") ? params.get("contextId").toString() : null;

        // Extract text from message parts
        String userInput = extractTextFromMessage(message);
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("No text content found in message parts");
        }

        // Resolve or create conversation
        String conversationId = resolveConversation(agentId, taskId, contextId);

        // Build InputData
        InputData inputData = new InputData();
        inputData.setInput(userInput);

        // Execute synchronously via ConversationService
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        conversationService.say(Environment.production, agentId, conversationId, false, true, null, inputData, false, snapshot -> {
            String response = "";
            if (snapshot != null && snapshot.getConversationOutputs() != null && !snapshot.getConversationOutputs().isEmpty()) {
                var outputs = snapshot.getConversationOutputs();
                var lastOutput = outputs.get(outputs.size() - 1);
                response = lastOutput != null ? lastOutput.toString() : "";
            }
            responseFuture.complete(response);
        });

        String response = responseFuture.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Build A2A response
        List<Part> responseParts = List.of(Part.textPart(response));
        A2AMessage responseMessage = new A2AMessage("agent", responseParts, null);
        Artifact artifact = new Artifact("response", null, responseParts, 0, null);

        return new A2ATask(taskId, contextId, TaskState.completed,
                List.of(new A2AMessage("user", List.of(Part.textPart(userInput)), null), responseMessage), List.of(artifact), null);
    }

    /**
     * Handle a {@code tasks/get} request — retrieve task status.
     */
    public A2ATask handleTaskGet(String taskId) {
        String conversationId = taskConversationCache.get(taskId);
        if (conversationId == null) {
            return null; // Task not found
        }

        try {
            var state = conversationService.getConversationState(conversationId);
            TaskState taskState = switch (state) {
                case READY -> TaskState.submitted;
                case IN_PROGRESS -> TaskState.working;
                case ENDED -> TaskState.completed;
                case ERROR -> TaskState.failed;
                default -> TaskState.unknown;
            };

            return new A2ATask(taskId, null, taskState, null, null, null);
        } catch (Exception e) {
            LOGGER.warnf("Failed to get task state for taskId=%s: %s", taskId, e.getMessage());
            return new A2ATask(taskId, null, TaskState.unknown, null, null, null);
        }
    }

    /**
     * Handle a {@code tasks/cancel} request — end the conversation.
     */
    public boolean handleTaskCancel(String taskId) {
        String conversationId = taskConversationCache.get(taskId);
        if (conversationId == null) {
            return false;
        }

        try {
            conversationService.endConversation(conversationId);
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Failed to cancel task %s: %s", taskId, e.getMessage());
            return false;
        }
    }

    // === Internal helpers ===

    private String resolveConversation(String agentId, String taskId, String contextId) throws Exception {
        // If contextId is provided, try to reuse an existing conversation
        if (contextId != null) {
            String existingConvId = contextConversationCache.get(contextId);
            if (existingConvId != null) {
                taskConversationCache.put(taskId, existingConvId);
                return existingConvId;
            }
        }

        // Start a new conversation
        var result = conversationService.startConversation(Environment.production, agentId, null, new LinkedHashMap<>());
        String conversationId = result.conversationId();

        // Cache the mapping
        taskConversationCache.put(taskId, conversationId);
        if (contextId != null) {
            contextConversationCache.put(contextId, conversationId);
        }

        return conversationId;
    }

    private String extractTextFromMessage(Map<String, Object> message) {
        Object partsObj = message.get("parts");
        if (partsObj instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> partMap) {
                    String type = (String) partMap.get("type");
                    if ("text".equals(type) || type == null) {
                        Object text = partMap.get("text");
                        if (text != null) {
                            return text.toString();
                        }
                    }
                }
            }
        }
        return null;
    }
}
