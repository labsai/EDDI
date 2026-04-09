package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;

import java.util.*;

import java.util.stream.IntStream;

/**
 * @author ginccc
 */
public class ConversationStep implements IConversationMemory.IWritableConversationStep {
    private Map<String, IData<?>> store;
    private final ConversationOutput conversationOutput;
    int conversationStepNumber;
    private String currentWorkflowId;

    ConversationStep(ConversationOutput conversationOutput) {
        store = new LinkedHashMap<>();
        this.conversationOutput = conversationOutput;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> IData<T> getData(String key) {
        return (IData<T>) store.get(key);
    }

    @Override
    public <T> List<IData<T>> getAllData(String prefix) {
        List<IData<T>> dataList = new ArrayList<>();

        for (String key : store.keySet()) {
            IData<T> data = getData(key);
            if (data != null) {
                if (key.startsWith(prefix))
                    dataList.add(data);
            }
        }

        return dataList;
    }

    @Override
    public void storeData(IData<?> data) {
        data.setOriginWorkflowId(this.currentWorkflowId);
        store.put(data.getKey(), data);
    }

    @Override
    public <T> void set(MemoryKey<T> key, T value) {
        Data<T> data = new Data<>(key.key(), value);
        data.setPublic(key.isPublic());
        storeData(data);
    }

    @Override
    public void removeData(String keyToBeRemoved) {
        store.entrySet().removeIf(dataEntry -> dataEntry.getValue().getKey().startsWith(keyToBeRemoved));
    }

    @Override
    public void setCurrentWorkflowId(String workflowId) {
        this.currentWorkflowId = workflowId;
    }

    public void resetConversationOutput(String rootKey) {
        conversationOutput.put(rootKey, new ArrayList<>());
    }

    @Override
    public void addConversationOutputObject(String key, Object value) {
        conversationOutput.put(key, value);
    }

    @Override
    public void replaceConversationOutputObject(String key, Object valueToBeReplaced, Object replace) {
        @SuppressWarnings("unchecked")
        var outputs = (List<Object>) conversationOutput.computeIfAbsent(key, k -> new ArrayList<>());

        IntStream.range(0, outputs.size()).forEach(i -> {
            var currentValue = outputs.get(i);
            if (currentValue.equals(valueToBeReplaced)) {
                outputs.set(i, replace);
            }
        });
    }

    @Override
    public void addConversationOutputString(String key, String value) {
        conversationOutput.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addConversationOutputList(String key, List<?> list) {
        var currentList = (List<Object>) conversationOutput.computeIfAbsent(key, k -> new ArrayList<>());

        currentList.addAll(list);
    }

    @Override
    public void addConversationOutputMap(String key, Map<String, Object> map) {
        @SuppressWarnings("unchecked")
        var currentMap = (Map<String, Object>) conversationOutput.computeIfAbsent(key, k -> new LinkedHashMap<String, Object>());

        currentMap.putAll(map);
    }

    @Override
    public ConversationOutput getConversationOutput() {
        return conversationOutput;
    }

    /**
     * Returns the current number of data elements in this step. Used by
     * LifecycleManager to determine which data entries were added by a task
     * (comparing before/after counts).
     *
     * @return current data element count
     * @since 6.0.0
     */
    public int snapshotDataCount() {
        return store.size();
    }

    /**
     * Returns a snapshot of the current ConversationOutput keys. Used by
     * LifecycleManager to rollback output entries added by a failed task.
     *
     * @return defensive copy of current output key set
     * @since 6.0.0
     */
    public Set<String> snapshotOutputKeys() {
        return new LinkedHashSet<>(conversationOutput.keySet());
    }

    @Override
    public Set<String> getAllKeys() {
        return store.keySet();
    }

    @Override
    public List<IData<?>> getAllElements() {
        return new ArrayList<>(store.values());
    }

    @Override
    public <T> IData<T> getLatestData(String prefix) {
        List<IData<?>> elements = getAllElements();
        Collections.reverse(elements);
        for (IData<?> element : elements) {
            if (element.getKey().startsWith(prefix)) {
                @SuppressWarnings("unchecked")
                IData<T> result = (IData<T>) element;
                return result;
            }
        }

        return null;
    }

    @Override
    public <T> IData<T> getLatestData(MemoryKey<T> key) {
        return getLatestData(key.key());
    }

    @Override
    public <T> IData<T> getData(MemoryKey<T> key) {
        return getData(key.key());
    }

    @Override
    public <T> T get(MemoryKey<T> key) {
        IData<T> data = getData(key.key());
        return data != null ? data.getResult() : null;
    }

    @Override
    public int size() {
        return store.size();
    }

    @Override
    public boolean isEmpty() {
        return store.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ConversationStep))
            return false;

        ConversationStep that = (ConversationStep) o;

        return store.equals(that.store);
    }

    @Override
    public int hashCode() {
        return store.hashCode();
    }

    @Override
    public String toString() {
        return "ConversationStep" + "{input=" + getLatestData("input") + ", output=" + getLatestData("output") + '}';
    }
}
