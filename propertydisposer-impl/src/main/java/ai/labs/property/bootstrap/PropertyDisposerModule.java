package ai.labs.property.bootstrap;

import ai.labs.property.IPropertyDisposer;
import ai.labs.property.impl.PropertyDisposer;
import ai.labs.runtime.bootstrap.AbstractBaseModule;

/**
 * @author ginccc
 */
public class PropertyDisposerModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IPropertyDisposer.class).to(PropertyDisposer.class);
    }
}
