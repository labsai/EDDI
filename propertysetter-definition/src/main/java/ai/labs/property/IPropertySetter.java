package ai.labs.property;

import ai.labs.expressions.Expressions;
import ai.labs.property.model.PropertyEntry;

import java.util.List;

/**
 * @author ginccc
 */
public interface IPropertySetter {
    List<PropertyEntry> extractProperties(Expressions expressions);
}
