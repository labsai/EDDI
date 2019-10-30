package ai.labs.rest.bootstrap;

import ai.labs.rest.restinterfaces.factory.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.factory.RestInterfaceFactory;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class RestInterfaceModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IRestInterfaceFactory.class).to(RestInterfaceFactory.class).in(Scopes.SINGLETON);
    }
}
