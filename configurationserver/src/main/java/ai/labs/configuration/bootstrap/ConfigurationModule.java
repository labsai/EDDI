package ai.labs.configuration.bootstrap;

import ai.labs.permission.ssl.SelfSignedTrustProvider;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.staticresources.rest.IRestEditor;
import ai.labs.staticresources.rest.impl.RestEditor;

/**
 * @author ginccc
 */
public class ConfigurationModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        SelfSignedTrustProvider.setAlwaysTrust(true);

        bind(IRestEditor.class).to(RestEditor.class);
    }
}
