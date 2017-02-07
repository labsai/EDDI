package ai.labs.memory;

import ai.labs.memory.model.ConversationState;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.Stack;

/**
 * @author ginccc
 */
public interface IConversationMemory extends Serializable {
    String getId();

    String getBotId();

    Integer getBotVersion();

    IWritableConversationStep getCurrentStep();

    IConversationStepStack getPreviousSteps();

    IConversationStepStack getAllSteps();

    int size();

    void undoLastStep();

    boolean isUndoAvailable();

    boolean isRedoAvailable();

    void redoLastStep();

    void setCurrentContext(String context);

    ConversationState getConversationState();

    void setConversationState(ConversationState conversationState);

    Stack<IConversationStep> getRedoCache();


    interface IConversationStepStack {
        IData getLatestData(String key);

        List<List<IData>> getAllData(String prefix);

        int size();

        IConversationStep get(int index);

        IConversationStep peek();
    }

    interface IConversationStep extends Serializable {
        IData getData(String key);

        List<IData> getAllData(String prefix);

        Set<String> getAllKeys();

        List<IData> getAllElements(IConversationMemory.IConversationContext context);

        IConversationContext getCurrentConversationContext();

        void setCurrentConversationContext(IConversationContext conversationContext);

        Set<IConversationContext> getAllConversationContexts();

        int size();

        boolean isEmpty();

        IData getLatestData();

        IData getLatestData(String prefix);
    }

    interface IWritableConversationStep extends IConversationStep {
        void storeData(IData element);
    }

    interface IConversationContext {
        String getContext();

        void setContext(String context);
    }

}
