package ai.labs.memory;

import java.util.*;

/**
 * @author ginccc
 */
public class ConversationStep implements IConversationMemory.IWritableConversationStep {
    protected Map<IConversationMemory.IConversationContext, Map<String, IData>> store;
    private IConversationMemory.IConversationContext conversationContext;
    protected int conversationStepNumber;

    public ConversationStep(IConversationMemory.IConversationContext conversationContext) {
        this.conversationContext = conversationContext;
        store = new LinkedHashMap<IConversationMemory.IConversationContext, Map<String, IData>>();
    }

    @Override
    public IData getData(String key) {
        return getCurrentContext().get(key);
    }

    private Map<String, IData> getCurrentContext() {
        if (!store.containsKey(conversationContext)) {
            store.put(new ConversationMemory.ConversationContext(conversationContext), new LinkedHashMap<String, IData>());
        }

        return store.get(conversationContext);
    }

    @Override
    public List<IData> getAllData(String prefix) {
        List<IData> dataList = new ArrayList<IData>();

        for (String key : getCurrentContext().keySet()) {
            IData data = getData(key);
            if (data != null) {
                if (key.startsWith(prefix))
                    dataList.add(data);
            }
        }

        return dataList;
    }

    @Override
    public void storeData(IData data) {
        Data value = new Data(data);
        getCurrentContext().put(data.getKey(), value);
    }

    @Override
    public Set<String> getAllKeys() {
        return getCurrentContext().keySet();
    }

    @Override
    public List<IData> getAllElements(IConversationMemory.IConversationContext context) {
        Map<String, IData> contextMap = store.get(context);
        return contextMap != null ? new ArrayList<IData>(contextMap.values()) : new ArrayList<IData>();
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
    public IData getLatestData() {
        List<IData> elements = getAllElements(conversationContext);
        if (elements.isEmpty())
            return null;

        return elements.get(elements.size() - 1);
    }

    @Override
    public IData getLatestData(String prefix) {
        List<IData> elements = getAllElements(conversationContext);
        Collections.reverse(elements);
        for (IData element : elements) {
            if (element.getKey().startsWith(prefix))
                return element;
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
        final StringBuilder sb = new StringBuilder();
        sb.append("ConversationStep");
        sb.append("{input=").append(getLatestData("input"));
        sb.append(", output=").append(getLatestData("output"));
        sb.append('}');
        return sb.toString();
    }
}
