package ai.labs.configuration.bootstrap;

import ai.labs.permission.ssl.SelfSignedTrustProvider;
import ai.labs.runtime.bootstrap.AbstractBaseModule;

/**
 * @author ginccc
 */
public class ConfigurationModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        SelfSignedTrustProvider.setAlwaysTrust(true);
    }
}
