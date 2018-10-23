package ai.labs.memory;

import ai.labs.memory.model.ConversationOutput;
import ai.labs.models.ConversationState;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.Stack;

/**
 * @author ginccc
 */
public interface IConversationMemory extends Serializable {
    String getConversationId();

    String getBotId();

    Integer getBotVersion();

    String getUserId();

    List<ConversationOutput> getConversationOutputs();

    IWritableConversationStep getCurrentStep();

    IConversationStepStack getPreviousSteps();

    IConversationStepStack getAllSteps();

    int size();

    void undoLastStep();

    boolean isUndoAvailable();

    boolean isRedoAvailable();

    void redoLastStep();

    ConversationState getConversationState();

    void setConversationState(ConversationState conversationState);

    Stack<IConversationStep> getRedoCache();


    interface IConversationStepStack {
        <T> IData<T> getLatestData(String key);

        <T> List<List<IData<T>>> getAllData(String prefix);

        int size();

        IConversationStep get(int index);

        IConversationStep peek();

        <T> List<IData<T>> getAllLatestData(String prefix);
    }

    interface IConversationStep extends Serializable {
        <T> IData<T> getData(String key);

        <T> List<IData<T>> getAllData(String prefix);

        Set<String> getAllKeys();

        List<IData> getAllElements();

        int size();

        boolean isEmpty();

        <T> IData<T> getLatestData(String prefix);
    }

    interface IWritableConversationStep extends IConversationStep {
        void storeData(IData element);

        void storeData(IData element, boolean publish);

        void resetConversationOutput(String rootKey);
    }
}
