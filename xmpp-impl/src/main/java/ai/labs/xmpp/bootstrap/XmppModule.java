package ai.labs.xmpp.bootstrap;

import ai.labs.xmpp.endpoint.IXmppEndpoint;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.xmpp.endpoint.XmppEndpoint;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;

/**
 * @author rpi
 */
public class XmppModule extends AbstractBaseModule {



    @Override
    protected void configure() {
        bind(IXmppEndpoint.class).to(XmppEndpoint.class).in(Scopes.SINGLETON);

    }
}
