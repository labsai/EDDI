package ai.labs.xmpp.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.xmpp.endpoint.IXmppEndpoint;
import ai.labs.xmpp.endpoint.XmppEndpoint;

/**
 * @author rpi
 */

public class XmppModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(IXmppEndpoint.class).to(XmppEndpoint.class);
    }
}
