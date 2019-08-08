package ai.labs.property;

import ai.labs.expressions.Expressions;
import ai.labs.models.Property;

import java.util.List;

/**
 * @author ginccc
 */
public interface IPropertySetter {
    List<Property> extractProperties(Expressions expressions);
}
