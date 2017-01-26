package ai.labs.rest.bootstrap;

import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.RestInterfaceFactory;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;

/**
 * @author ginccc
 */
public class RestInterfaceModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(HttpClient.class).to(DefaultHttpClient.class).in(Scopes.SINGLETON);
        bind(IRestInterfaceFactory.class).to(RestInterfaceFactory.class).in(Scopes.SINGLETON);
    }
}
