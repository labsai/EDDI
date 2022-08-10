package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;

import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

/**
 * @author ginccc
 */
public class ConversationStep implements IConversationMemory.IWritableConversationStep {
    private Map<String, IData> store;
    private final ConversationOutput conversationOutput;
    int conversationStepNumber;
    private String currentPackageId;

    ConversationStep(ConversationOutput conversationOutput) {
        store = new LinkedHashMap<>();
        this.conversationOutput = conversationOutput;
    }

    @Override
    public <T> IData<T> getData(String key) {
        return store.get(key);
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
    public void storeData(IData data) {
        data.setOriginPackageId(this.currentPackageId);
        store.put(data.getKey(), data);
    }

    @Override
    public void removeData(String keyToBeRemoved) {
        store.entrySet().removeIf(dataEntry -> dataEntry.getValue().getKey().startsWith(keyToBeRemoved));
    }

    @Override
    public void setCurrentPackageId(String packageId) {
        this.currentPackageId = packageId;
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
        var outputs = (List<Object>) conversationOutput.
                computeIfAbsent(key, (Function<String, List>) k -> new ArrayList());

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
    public void addConversationOutputList(String key, List list) {
        var currentList = (List<Object>) conversationOutput.
                computeIfAbsent(key, (Function<String, List>) k -> new ArrayList());

        currentList.addAll(list);
    }

    @Override
    public void addConversationOutputMap(String key, Map<String, Object> map) {
        var currentMap = (Map<String, Object>) conversationOutput.
                computeIfAbsent(key, (Function<String, Map>) k -> new LinkedHashMap<String, Object>());

        currentMap.putAll(map);
    }

    @Override
    public ConversationOutput getConversationOutput() {
        return conversationOutput;
    }

    @Override
    public Set<String> getAllKeys() {
        return store.keySet();
    }

    @Override
    public List<IData> getAllElements() {
        return new ArrayList<>(store.values());
    }


    @Override
    public <T> IData<T> getLatestData(String prefix) {
        List<IData> elements = getAllElements();
        Collections.reverse(elements);
        for (IData element : elements) {
            if (element.getKey().startsWith(prefix)) {
                return element;
            }
        }

        return null;
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
        if (this == o) return true;
        if (!(o instanceof ConversationStep)) return false;

        ConversationStep that = (ConversationStep) o;

        return store.equals(that.store);
    }

    @Override
    public int hashCode() {
        return store.hashCode();
    }

    @Override
    public String toString() {
        return "ConversationStep" +
                "{input=" + getLatestData("input") +
                ", output=" + getLatestData("output") +
                '}';
    }
}
