package ai.labs.channels.differ.model.events;

import ai.labs.channels.differ.model.events.ConversationCreatedEvent.Conversation;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class MessageCreatedEvent extends Event implements Serializable {
    private MessageCreatedPayload payload;

    @Getter
    @Setter
    public static class MessageCreatedPayload extends Payload implements Serializable {
        private String communityId;
        private List<String> participantIds;
        private Conversation conversation;
        private Message message;
    }

    @Getter
    @Setter
    public static class Message implements Serializable {
        private String id;
        private String conversationId;
        private String inputType;
        private List<String> mentions;
        private List<Event.Part> parts;
        private String senderId;
        private Date sentAt;
    }
}
