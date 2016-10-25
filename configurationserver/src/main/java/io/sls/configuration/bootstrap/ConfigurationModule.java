package io.sls.configuration.bootstrap;

import io.sls.permission.ssl.SelfSignedTrustProvider;
import io.sls.runtime.bootstrap.AbstractBaseModule;
import io.sls.staticresources.rest.IRestEditor;
import io.sls.staticresources.rest.impl.RestEditor;

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
