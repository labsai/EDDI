package ai.labs.memory;

import java.util.*;

/**
 * @author ginccc
 */
public class ConversationStep implements IConversationMemory.IWritableConversationStep {
    private Map<IConversationMemory.IConversationContext, Map<String, IData>> store;
    private IConversationMemory.IConversationContext conversationContext;
    int conversationStepNumber;

    ConversationStep(IConversationMemory.IConversationContext conversationContext) {
        this.conversationContext = conversationContext;
        store = new LinkedHashMap<>();
    }

    @Override
    public <T> IData<T> getData(String key) {
        return getCurrentContext().get(key);
    }

    private Map<String, IData> getCurrentContext() {
        if (!store.containsKey(conversationContext)) {
            store.put(new ConversationMemory.ConversationContext(conversationContext), new LinkedHashMap<>());
        }

        return store.get(conversationContext);
    }

    @Override
    public <T> List<IData<T>> getAllData(String prefix) {
        List<IData<T>> dataList = new ArrayList<>();

        for (String key : getCurrentContext().keySet()) {
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
        getCurrentContext().put(data.getKey(), data);
    }

    @Override
    public Set<String> getAllKeys() {
        return getCurrentContext().keySet();
    }

    @Override
    public List<IData> getAllElements(IConversationMemory.IConversationContext context) {
        Map<String, IData> contextMap = store.get(context);
        return contextMap != null ? new ArrayList<>(contextMap.values()) : new ArrayList<>();
    }

    @Override
    public IConversationMemory.IConversationContext getCurrentConversationContext() {
        return conversationContext;
    }

    @Override
    public void setCurrentConversationContext(IConversationMemory.IConversationContext conversationContext) {
        this.conversationContext = conversationContext;
    }

    @Override
    public Set<IConversationMemory.IConversationContext> getAllConversationContexts() {
        return store.keySet();
    }

    @Override
    public <T> IData<T> getLatestData(String prefix) {
        List<IData> elements = getAllElements(conversationContext);
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
        return getCurrentContext().size();
    }

    @Override
    public boolean isEmpty() {
        return getCurrentContext().isEmpty();
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
