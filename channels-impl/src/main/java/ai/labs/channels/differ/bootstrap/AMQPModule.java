package ai.labs.channels.differ.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import javax.inject.Named;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class AMQPModule extends AbstractBaseModule {
    @Override
    protected void configure() {

    }

    @Provides
    @Singleton
    private ConnectionFactory provideConnectionFactory(@Named("amqp.host") String host,
                                                       @Named("amqp.port") Integer port) {
        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(host);
        connectionFactory.setPort(port);
        return connectionFactory;
    }

    @Provides
    @Singleton
    private Channel provideChannel(ConnectionFactory connectionFactory) throws IOException, TimeoutException {
        try {
            Connection connection = connectionFactory.newConnection();
            return connection.createChannel();
        } catch (IOException | TimeoutException e) {
            throw e;
        }
    }
}
