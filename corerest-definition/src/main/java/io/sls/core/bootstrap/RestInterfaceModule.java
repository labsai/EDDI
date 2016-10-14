package io.sls.core.bootstrap;

import com.google.inject.Scopes;
import io.sls.core.service.restinterfaces.IRestInterfaceFactory;
import io.sls.core.service.restinterfaces.RestInterfaceFactory;
import io.sls.runtime.bootstrap.AbstractBaseModule;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;

/**
 * Created by jariscgr on 22.08.2016.
 */
public class RestInterfaceModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(HttpClient.class).to(DefaultHttpClient.class).in(Scopes.SINGLETON);
        bind(IRestInterfaceFactory.class).to(RestInterfaceFactory.class).in(Scopes.SINGLETON);
    }
}
