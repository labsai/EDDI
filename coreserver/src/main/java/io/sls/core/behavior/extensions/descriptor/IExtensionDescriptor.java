package io.sls.core.behavior.extensions.descriptor;

import java.util.List;

/**
 * User: jarisch
 * Date: 01.05.12
 * Time: 15:54
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
