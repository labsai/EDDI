package ai.labs.property;

import ai.labs.property.model.PropertyEntry;

import java.util.List;

/**
 * @author ginccc
 */
public interface IPropertyDisposer {
    List<PropertyEntry> extractProperties(String expressions);
}
