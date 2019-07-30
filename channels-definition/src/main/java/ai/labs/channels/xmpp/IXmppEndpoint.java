package ai.labs.channels.xmpp;

import ai.labs.channels.config.model.ChannelDefinition;

public interface IXmppEndpoint {
    String RESOURCE_URI_XMPP_CHANNEL_CONNECTOR = "eddi://ai.labs.channel.xmpp";

    void init(ChannelDefinition channelDefinition);
}
