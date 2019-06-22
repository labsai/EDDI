package ai.labs.xmpp.endpoint;

import org.jivesoftware.smack.chat2.IncomingChatMessageListener;

public interface IXmppEndpoint extends IncomingChatMessageListener {

    void init();
}
