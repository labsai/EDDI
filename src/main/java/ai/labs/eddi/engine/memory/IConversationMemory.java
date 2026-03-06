package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.model.ConversationState;
import ai.labs.eddi.configs.properties.model.Property;

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

    /**
     * Get the event sink for streaming SSE events. Returns {@code null} when
     * no streaming is requested (standard say endpoint).
     */
    default ConversationEventSink getEventSink() {
        return null;
    }

    /**
     * Set the event sink for this conversation turn. Called from
     * {@code ConversationService.sayStreaming()} before lifecycle execution.
     */
    default void setEventSink(ConversationEventSink eventSink) {
        // no-op by default
    }

    interface IConversationStepStack {
        <T> IData<T> getLatestData(String key);

        /** Type-safe variant of {@link #getLatestData(String)}. */
        <T> IData<T> getLatestData(MemoryKey<T> key);

        <T> List<List<IData<T>>> getAllData(String prefix);

        int size();

        IConversationStep get(int index);

        IConversationStep peek();

        <T> List<IData<T>> getAllLatestData(String prefix);
    }

    interface IConversationStep extends Serializable {
        <T> IData<T> getData(String key);

        /** Type-safe variant of {@link #getData(String)}. */
        <T> IData<T> getData(MemoryKey<T> key);

        /**
         * Convenience method: returns the value directly, or {@code null} if not
         * present.
         * Equivalent to {@code getData(key) != null ? getData(key).getResult() : null}.
         */
        <T> T get(MemoryKey<T> key);

        <T> List<IData<T>> getAllData(String prefix);

        Set<String> getAllKeys();

        List<IData> getAllElements();

        int size();

        boolean isEmpty();

        <T> IData<T> getLatestData(String prefix);

        /** Type-safe variant of {@link #getLatestData(String)}. */
        <T> IData<T> getLatestData(MemoryKey<T> key);

        ConversationOutput getConversationOutput();
    }

    interface IWritableConversationStep extends IConversationStep {
        void storeData(IData element);

        /**
         * Type-safe store: creates a {@link IData} wrapper, sets the public flag
         * from the key, and stores it in this step.
         */
        <T> void set(MemoryKey<T> key, T value);

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
