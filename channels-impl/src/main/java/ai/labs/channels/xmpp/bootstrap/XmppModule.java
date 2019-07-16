package ai.labs.channels.xmpp.bootstrap;

import ai.labs.channels.xmpp.IXmppEndpoint;
import ai.labs.channels.xmpp.XmppEndpoint;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

/**
 * @author rpi
 */

public class XmppModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(IXmppEndpoint.class).to(XmppEndpoint.class).in(Scopes.SINGLETON);
    }
}
