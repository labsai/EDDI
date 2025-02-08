package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.configs.properties.model.Property;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class ConversationProperties
        extends HashMap<String, Property>
        implements IConversationMemory.IConversationProperties {

    private static final String KEY_PROPERTIES = "properties";
    private final Map<String, Object> propertiesMap = new LinkedHashMap<>();

    @JsonIgnore
    private final IConversationMemory conversationMemory;

    public ConversationProperties(IConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    @Override
    public Property put(String key, Property property) {
        if (conversationMemory != null) {
            String propertiesKey = KEY_PROPERTIES + ":" + key;
            IConversationMemory.IWritableConversationStep currentStep = conversationMemory.getCurrentStep();
            currentStep.storeData(new Data<>(propertiesKey, Collections.singletonList(property)));
            Map<String, Object> propertyMap = new LinkedHashMap<>();
            if (property.getValueString() != null) {
                propertyMap.put(property.getName(), property.getValueString());
            } else if (property.getValueObject() != null) {
                propertyMap.put(property.getName(), property.getValueObject());
            } else if (property.getValueList() != null) {
                propertyMap.put(property.getName(), property.getValueList());
            } else if (property.getValueInt() != null) {
                propertyMap.put(property.getName(), property.getValueInt());
            } else if (property.getValueFloat() != null) {
                propertyMap.put(property.getName(), property.getValueFloat());
            } else if (property.getValueBoolean() != null) {
                propertyMap.put(property.getName(), property.getValueBoolean());
            }

            propertiesMap.putAll(propertyMap);
            currentStep.addConversationOutputMap(KEY_PROPERTIES, propertyMap);
        }

        return super.put(key, property);
    }

    @Override
    public void putAll(Map<? extends String, ? extends Property> map) {
        map.keySet().forEach(key -> put(key, map.get(key)));
    }

    @Override
    public Map<String, Object> toMap() {
        return propertiesMap;
    }
}
