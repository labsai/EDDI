package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.models.ConversationState;
import ai.labs.eddi.models.Property;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
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

    IConversationProperties getConversationProperties();

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

        ConversationOutput getConversationOutput();
    }

    interface IWritableConversationStep extends IConversationStep {
        void storeData(IData element);

        void removeData(String key);

        void setCurrentPackageId(String packageId);

        void resetConversationOutput(String rootKey);

        void addConversationOutputObject(String key, Object value);

        void replaceConversationOutputObject(String key, Object value, Object replace);

        void addConversationOutputString(String key, String value);

        void addConversationOutputList(String key, List list);

        void addConversationOutputMap(String key, Map<String, Object> map);
    }

    interface IConversationProperties extends Map<String, Property> {
        Map<String, Object> toMap();
    }
}
