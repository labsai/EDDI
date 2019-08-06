package ai.labs.channels.differ;

import ai.labs.channels.differ.model.CommandInfo;
import ai.labs.channels.differ.model.commands.Command;
import ai.labs.channels.differ.model.commands.CreateMessageCommand;
import ai.labs.channels.differ.model.events.Event;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.serialization.JsonSerialization;
import ai.labs.serialization.bootstrap.SerializationModule;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeoutException;

import static ai.labs.channels.differ.DifferOutputTransformer.INPUT_TYPE_TEXT;
import static ai.labs.channels.differ.DifferPublisher.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

public class DifferPublisherTest {

    private IDifferPublisher differPublisher;
    private Channel channel;
    private IJsonSerialization jsonSerialization;

    @Before
    public void setUp() {
        channel = mock(Channel.class);
        jsonSerialization = createDummyJsonSerializer();

        differPublisher = new DifferPublisher(channel, jsonSerialization);
    }

    private IJsonSerialization createDummyJsonSerializer() {
        ObjectMapper objectMapper = new SerializationModule().provideObjectMapper(false);
        return new JsonSerialization(objectMapper);
    }

    @Test
    public void positiveDeliveryAck() throws IOException {
        //setup
        final long deliveryTag = 1L;

        //test
        differPublisher.positiveDeliveryAck(deliveryTag);

        //assert
        Mockito.verify(channel).basicAck(eq(deliveryTag), eq(false));
    }

    @Test
    public void negativeDeliveryAck() throws IOException, TimeoutException, InterruptedException {
        //setup
        long deliveryTag = 1L;
        String exchange = "someExchange";
        String routingKey = "someRoutingKey";
        AMQP.BasicProperties properties = new AMQP.BasicProperties();
        byte[] body = new byte[0];
        Delivery delivery = new Delivery(new Envelope(deliveryTag, false, exchange, routingKey), properties, body);

        //test
        differPublisher.negativeDeliveryAck(delivery);

        //assert
        Mockito.verify(channel).basicNack(eq(deliveryTag), eq(false), eq(false));
        Mockito.verify(channel).basicPublish(
                eq(MESSAGE_CREATED_EXCHANGE), eq(MESSAGE_CREATED_EDDI_FAILED_ROUTING_KEY), eq(null), eq(body));
        Mockito.verify(channel).waitForConfirmsOrDie(eq(TIMEOUT_CONFIRMS_IN_MILLIS));
    }

    @Test
    public void publishCommandAndWaitForConfirm() throws IOException {
        //setup
        CommandInfo commandInfo = new CommandInfo();
        commandInfo.setExchange("testExchange");
        commandInfo.setRoutingKey("testRoutingKey");
        var part = new Event.Part("Some Message", "text/plain", INPUT_TYPE_TEXT);
        var createMessageCommandPayload = new CreateMessageCommand.Payload(
                "conversationId", "senderId", INPUT_TYPE_TEXT, Collections.singletonList(part));
        var command = new CreateMessageCommand(
                new Command.AuthContext("someUserId"), "commandName", createMessageCommandPayload);
        commandInfo.setCommand(command);
        commandInfo.setMinSentAt(System.currentTimeMillis());

        //test
        differPublisher.publishCommandAndWaitForConfirm(commandInfo);

        //assert
        Mockito.verify(channel).basicPublish(
                eq(commandInfo.getExchange()),
                eq(commandInfo.getRoutingKey()),
                eq(null), eq(jsonSerialization.serialize(command).getBytes()));
    }
}