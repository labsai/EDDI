package ai.labs.channels.differ.model.events;

import ai.labs.channels.differ.model.events.MessageCreatedEvent.Message;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Date;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class ActionTriggeredEvent extends Event implements Serializable {
    private ActionTriggeredPayload payload;

    @Getter
    @Setter
    public static class ActionTriggeredPayload extends Payload implements Serializable {
        private String userId;
        private Action action;
        private Message message;
    }

    @Getter
    @Setter
    public static class Action implements Serializable {
        private String id;
        private String conversationId;
        private String createdById;
        private String messageId;
        private String text;
        private Date updatedAt;
        private Date createdAt;
        private Boolean primary;
    }
}
