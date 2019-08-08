package ai.labs.channels.differ;

import ai.labs.channels.differ.model.CommandInfo;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import java.io.IOException;

public interface IDifferPublisher {
    void init(DeliverCallback conversationCreatedCallback, DeliverCallback messageCreatedCallback);

    void positiveDeliveryAck(long deliveryTag);

    void negativeDeliveryAck(Delivery delivery);

    boolean publishCommandAndWaitForConfirm(CommandInfo commandInfo) throws IOException;
}
