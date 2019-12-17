package ai.labs.channels.differ;

import ai.labs.channels.differ.model.CommandInfo;
import ai.labs.channels.differ.model.commands.CreateMessageCommand;
import ai.labs.serialization.IJsonSerialization;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.TimeoutException;

import static ai.labs.channels.differ.utilities.DifferUtilities.calculateSentAt;

@Slf4j
@Singleton
public class DifferPublisher implements IDifferPublisher {
    private static final String MESSAGE_CREATED_EXCHANGE = "message.created";
    static final String EDDI_EXCHANGE = "eddi";
    private static final String MESSAGE_CREATED_QUEUE_NAME = MESSAGE_CREATED_EXCHANGE + ".eddi";
    private static final String CONVERSATION_CREATED_EXCHANGE = "conversation.created";
    private static final String CONVERSATION_CREATED_QUEUE_NAME = CONVERSATION_CREATED_EXCHANGE + ".eddi";

    static final String MESSAGE_CREATED_EDDI_FAILED_ROUTING_KEY = MESSAGE_CREATED_EXCHANGE + ".eddi.failed-events";
    static final long TIMEOUT_CONFIRMS_IN_MILLIS = 60000;

    private final Provider<Channel> channelProvider;
    private final IJsonSerialization jsonSerialization;

    private final CancelCallback cancelCallback = consumerTag -> {
    };
    private Channel channel;

    @Inject
    public DifferPublisher(Provider<Channel> channelProvider, IJsonSerialization jsonSerialization) {
        this.channelProvider = channelProvider;
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public void init(DeliverCallback conversationCreatedCallback, DeliverCallback messageCreatedCallback) {
        try {
            channel = channelProvider.get();

            channel.exchangeDeclare(EDDI_EXCHANGE, BuiltinExchangeType.TOPIC, true, false, null);

            channel.queueDeclare(CONVERSATION_CREATED_QUEUE_NAME, true, false, false, null);
            channel.queueBind(CONVERSATION_CREATED_QUEUE_NAME, CONVERSATION_CREATED_EXCHANGE, "");
            channel.basicConsume(CONVERSATION_CREATED_QUEUE_NAME, false, conversationCreatedCallback, cancelCallback);

            channel.queueDeclare(MESSAGE_CREATED_QUEUE_NAME, true, false, false, null);
            channel.queueBind(MESSAGE_CREATED_QUEUE_NAME, MESSAGE_CREATED_EXCHANGE, "");
            channel.basicConsume(MESSAGE_CREATED_QUEUE_NAME, false, messageCreatedCallback, cancelCallback);

            channel.queueDeclare(MESSAGE_CREATED_EDDI_FAILED_ROUTING_KEY, true, false, false, null);
            channel.queueBind(MESSAGE_CREATED_EDDI_FAILED_ROUTING_KEY, EDDI_EXCHANGE, "");

            channel.confirmSelect();

        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void positiveDeliveryAck(long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
            log.debug("Send positive Ack (deliveryTag={})", deliveryTag);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void negativeDeliveryAck(Delivery delivery) {
        try {
            channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
            log.debug("Send negative Ack (deliveryTag={})", delivery.getEnvelope().getDeliveryTag());
            publishEventToFailedQueue(delivery);

        } catch (IOException e) {
            log.error("Could not publish message.created event on eddi.failed queue! \n"
                    + e.getLocalizedMessage(), e);
        }
    }

    private void publishEventToFailedQueue(Delivery delivery) {
        try {
            channel.basicPublish(
                    EDDI_EXCHANGE,
                    MESSAGE_CREATED_EDDI_FAILED_ROUTING_KEY, null, delivery.getBody()
            );

            channel.waitForConfirmsOrDie(TIMEOUT_CONFIRMS_IN_MILLIS);
            log.debug("Published event to FAILED MESSAGE queue \"{}\". {}", MESSAGE_CREATED_EDDI_FAILED_ROUTING_KEY, delivery);
        } catch (IOException | InterruptedException | TimeoutException e) {
            log.error("Could not publish message.created event on eddi.failed queue! {}", new String(delivery.getBody()));
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public boolean publishCommandAndWaitForConfirm(CommandInfo commandInfo)
            throws IOException {

        var command = commandInfo.getCommand();
        long calculateSentAt = calculateSentAt(commandInfo.getMinSentAt());
        int sequenceNumber = commandInfo.getSequenceNumber();
        Date sentAt = new Date(calculateSentAt + sequenceNumber);
        command.setCreatedAt(sentAt);
        if (command instanceof CreateMessageCommand) {
            ((CreateMessageCommand) command).getPayload().setSentAt(sentAt);
        }
        log.debug("sendAt: {} = calculateSentAt: {} + sequenceNumber: {}, ",
                sentAt.getTime(), calculateSentAt, sequenceNumber);

        String eventBody = jsonSerialization.serialize(command);
        channel.basicPublish(
                commandInfo.getExchange(),
                commandInfo.getRoutingKey(),
                null, eventBody.getBytes());

        try {
            channel.waitForConfirmsOrDie(TIMEOUT_CONFIRMS_IN_MILLIS);
            log.debug("Published command: {}", eventBody);
            return true;
        } catch (InterruptedException | TimeoutException e) {
            log.error(e.getLocalizedMessage(), e);
            return false;
        }
    }
}
