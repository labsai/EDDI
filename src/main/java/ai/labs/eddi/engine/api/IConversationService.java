package ai.labs.eddi.engine.api;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Core conversation lifecycle service — extracted from RestAgentEngine. All
 * conversation operations are available here without JAX-RS dependencies.
 *
 * @author ginccc
 */
public interface IConversationService {

    record ConversationResult(String conversationId, URI conversationUri) {
    }

    /**
     * Start a new conversation with the latest ready Agent version.
     *
     * @throws AgentNotReadyException
     *             if no version of the Agent is deployed
     * @throws ResourceStoreException
     *             on persistence failures
     * @throws ResourceNotFoundException
     *             if referenced resources are not found
     */
    ConversationResult startConversation(Environment environment, String agentId, String userId, Map<String, Context> context)
            throws AgentNotReadyException, ResourceStoreException, ResourceNotFoundException;

    /**
     * End a conversation by setting its state to ENDED.
     */
    void endConversation(String conversationId);

    /**
     * Get the current state of a conversation (from cache or DB).
     *
     * @throws ConversationNotFoundException
     *             if no conversation exists with the given ID
     */
    ConversationState getConversationState(Environment environment, String conversationId);

    /**
     * Read a conversation memory snapshot.
     *
     * @throws AgentMismatchException
     *             if the conversationId does not belong to the given agentId
     * @throws ResourceStoreException
     *             on persistence failures
     * @throws ResourceNotFoundException
     *             if the conversation is not found
     */
    SimpleConversationMemorySnapshot readConversation(Environment environment, String agentId, String conversationId, Boolean returnDetailed,
            Boolean returnCurrentStepOnly, List<String> returningFields)
            throws AgentMismatchException, ResourceStoreException, ResourceNotFoundException;

    /**
     * Read conversation log in text or structured format.
     *
     * @throws ResourceStoreException
     *             on persistence failures
     * @throws ResourceNotFoundException
     *             if the conversation is not found
     */
    ConversationLogResult readConversationLog(String conversationId, String outputType, Integer logSize)
            throws ResourceStoreException, ResourceNotFoundException;

    record ConversationLogResult(Object content, String mediaType) {
    }

    /**
     * Process a user input (say) or rerun the last step. Results are delivered via
     * the responseHandler callback.
     *
     * @throws AgentMismatchException
     *             if agentId doesn't match the conversation's agent
     * @throws ConversationEndedException
     *             if the conversation has already ended
     * @throws ResourceNotFoundException
     *             if the conversation is not found
     */
    void say(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
            List<String> returningFields, InputData inputData, boolean rerunOnly, ConversationResponseHandler responseHandler) throws Exception;

    @FunctionalInterface
    interface ConversationResponseHandler {
        void onComplete(SimpleConversationMemorySnapshot snapshot);
    }

    /**
     * Callback for streaming conversation responses via SSE.
     */
    interface StreamingResponseHandler {
        void onTaskStart(String taskId, String taskType, int index);

        void onTaskComplete(String taskId, String taskType, long durationMs, Map<String, Object> summary);

        void onToken(String token);

        void onComplete(SimpleConversationMemorySnapshot snapshot);

        void onError(Throwable error);
    }

    /**
     * Process user input with SSE streaming. Token and step events are delivered
     * via the streamingHandler callback.
     */
    void sayStreaming(Environment environment, String agentId, String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
            List<String> returningFields, InputData inputData, StreamingResponseHandler streamingHandler) throws Exception;

    Boolean isUndoAvailable(Environment environment, String agentId, String conversationId) throws ResourceStoreException, ResourceNotFoundException;

    /**
     * @return true if undo was performed, false if not available
     */
    boolean undo(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException;

    Boolean isRedoAvailable(Environment environment, String agentId, String conversationId) throws ResourceStoreException, ResourceNotFoundException;

    /**
     * @return true if redo was performed, false if not available
     */
    boolean redo(Environment environment, String agentId, String conversationId)
            throws ResourceStoreException, ResourceNotFoundException, AgentMismatchException;

    // --- Domain exceptions (no JAX-RS dependency) ---

    class AgentNotReadyException extends Exception {
        public AgentNotReadyException(String message) {
            super(message);
        }
    }

    class ConversationNotFoundException extends RuntimeException {
        public ConversationNotFoundException(String message) {
            super(message);
        }
    }

    class AgentMismatchException extends Exception {
        public AgentMismatchException(String message) {
            super(message);
        }
    }

    class ConversationEndedException extends Exception {
        public ConversationEndedException(String message) {
            super(message);
        }
    }
}
