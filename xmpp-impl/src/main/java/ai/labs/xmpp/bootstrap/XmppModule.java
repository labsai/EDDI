package ai.labs.xmpp.bootstrap;

import ai.labs.xmpp.endpoint.IXmppEndpoint;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.xmpp.endpoint.XmppEndpoint;
import com.google.inject.Scopes;
import lombok.extern.slf4j.Slf4j;

/**
 * @author rpi
 */
@Slf4j
public class XmppModule extends AbstractBaseModule {



    @Override
    protected void configure() {
        log.info("XMPP configure");
        bind(IXmppEndpoint.class).to(XmppEndpoint.class);

    }
}
