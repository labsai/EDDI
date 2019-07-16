package ai.labs.channels.facebookmessenger.bootstrap;

import ai.labs.channels.facebookmessenger.FacebookEndpoint;
import ai.labs.channels.facebookmessenger.IFacebookEndpoint;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class FacebookMessengerModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IFacebookEndpoint.class).to(FacebookEndpoint.class).in(Scopes.SINGLETON);
    }
}
