package io.sls.core.behavior.extensions.descriptor;

import java.util.List;

/**
 * @author ginccc
 */
public interface IExtensionDescriptor {

    String getId();

    String getDescription();

    int getMaxChildCount();

    List<Attribute> getAttributes();

    interface Attribute {
        String getName();

        String getType();

        String getDescription();
    }

    Object createInstance() throws ClassNotFoundException, InstantiationException, IllegalAccessException;
}
