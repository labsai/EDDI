package ai.labs.property;

import ai.labs.expressions.Expression;
import ai.labs.property.model.PropertyEntry;

import java.util.List;

/**
 * @author ginccc
 */
public interface IPropertySetter {
    List<PropertyEntry> extractProperties(List<Expression> expressions);
}
