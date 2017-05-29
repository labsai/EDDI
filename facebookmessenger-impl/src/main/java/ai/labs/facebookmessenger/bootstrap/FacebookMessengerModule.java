package ai.labs.facebookmessenger.bootstrap;

import ai.labs.facebookmessenger.endpoint.FacebookEndpoint;
import ai.labs.facebookmessenger.endpoint.IFacebookEndpoint;
import ai.labs.runtime.bootstrap.AbstractBaseModule;

/**
 * @author ginccc
 */
public class FacebookMessengerModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IFacebookEndpoint.class).to(FacebookEndpoint.class);
    }
}
