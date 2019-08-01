package ai.labs.channels.config;

import ai.labs.channels.config.model.ChannelDefinition;

/**
 * @author ginccc
 */
public interface IChannelManager {
    void initChannel(ChannelDefinition channelDefinition);

    class ChannelInitializationException extends Exception {
        public ChannelInitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
