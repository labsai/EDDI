package ai.labs.eddi.modules.properties;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.nlp.expressions.Expressions;

import java.util.List;

/**
 * @author ginccc
 */
public interface IPropertySetter {
    List<SetOnActions> getSetOnActionsList();

    List<Property> extractProperties(Expressions expressions);
}
