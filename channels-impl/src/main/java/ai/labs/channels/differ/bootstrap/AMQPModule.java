package ai.labs.channels.differ.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Provides;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import javax.inject.Named;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

public class AMQPModule extends AbstractBaseModule {
    @Override
    protected void configure() {

    }

    @Provides
    private ConnectionFactory provideConnectionFactory(@Named("amqp.host") String host,
                                                       @Named("amqp.port") Integer port) {
        var connectionFactory = new ConnectionFactory();
        connectionFactory.setHost(host);
        connectionFactory.setPort(port);
        return connectionFactory;
    }

    @Provides
    private Channel provideChannel(ConnectionFactory connectionFactory) throws IOException, TimeoutException {
        Connection connection = connectionFactory.newConnection();
        registerRabbitMQShutdownHook(connection);
        return connection.createChannel();
    }

    private void registerRabbitMQShutdownHook(final Connection connection) {
        Runtime.getRuntime().addShutdownHook(new Thread("ShutdownHook_RabbitMQClient") {
            @Override
            public void run() {
                try {
                    connection.close(AMQP.REPLY_SUCCESS,
                            "Closing RabbitMQ Connection because EDDI is shutting down!",
                            10000);
                } catch (Throwable e) {
                    String message = "RabbitMQClient did not stop as expected.";
                    System.out.println(message);
                    System.out.println(Arrays.toString(e.getStackTrace()));
                }
            }
        });
    }
}
