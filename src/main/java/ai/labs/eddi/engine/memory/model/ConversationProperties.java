package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.models.Property;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

public class ConversationProperties
        extends HashMap<String, Property>
        implements IConversationMemory.IConversationProperties {

    private static final String KEY_PROPERTIES = "properties";
    private Map<String, Object> propertiesMap = new LinkedHashMap<>();

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
            var valueString = property.getValueString();
            if (!isNullOrEmpty(valueString)) {
                propertyMap.put(property.getName(), valueString);
            } else {
                var valueObject = property.getValueObject();
                if (!isNullOrEmpty(valueObject)) {
                    propertyMap.put(property.getName(), valueObject);
                }
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
