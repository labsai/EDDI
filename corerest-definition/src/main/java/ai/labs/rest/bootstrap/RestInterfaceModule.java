package ai.labs.rest.bootstrap;

import ai.labs.rest.restinterfaces.IRestInterfaceFactory;
import ai.labs.rest.restinterfaces.RestInterfaceFactory;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

import javax.inject.Singleton;

/**
 * @author ginccc
 */
public class RestInterfaceModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IRestInterfaceFactory.class).to(RestInterfaceFactory.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    HttpClient provideHttpClient() {
        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create();
        return httpClientBuilder.build();
    }
}
