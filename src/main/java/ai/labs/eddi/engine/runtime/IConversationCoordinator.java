package ai.labs.eddi.engine.runtime;

/**
 * Conversation coordinator — ensures sequential message processing per conversation.
 * Extends {@link IEventBus} to inherit the pluggable event bus contract.
 *
 * @author ginccc
 */
public interface IConversationCoordinator extends IEventBus {
    // submitInOrder is inherited from IEventBus
}
