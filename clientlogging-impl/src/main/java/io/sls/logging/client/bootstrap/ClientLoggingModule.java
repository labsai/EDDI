package io.sls.logging.client.bootstrap;

import com.google.inject.Scopes;
import io.sls.logging.client.IClientLogging;
import io.sls.logging.client.impl.ClientLogging;
import io.sls.logging.client.rest.IRestClientLogging;
import io.sls.logging.client.rest.impl.RestClientLogging;
import io.sls.runtime.bootstrap.AbstractBaseModule;

/**
 * @author ginccc
 */
public class ClientLoggingModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IClientLogging.class).to(ClientLogging.class).in(Scopes.SINGLETON);

        bind(IRestClientLogging.class).to(RestClientLogging.class);
    }
}
