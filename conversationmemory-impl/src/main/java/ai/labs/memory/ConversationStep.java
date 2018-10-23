package ai.labs.memory;

import java.util.*;

/**
 * @author ginccc
 */
public class ConversationStep implements IConversationMemory.IWritableConversationStep {
    private Map<String, IData> store;
    int conversationStepNumber;

    ConversationStep() {
        store = new LinkedHashMap<>();
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
        store.put(data.getKey(), data);
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
