package ai.labs.channels.differ.model.events;

import ai.labs.channels.differ.model.events.ConversationCreatedEvent.Conversation;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@ToString
@EqualsAndHashCode
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
        private List<Mention> mentions;
        private List<Event.Part> parts;
        private String senderId;
        private Date sentAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Mention implements Serializable {
        private String id;
        private String tag;
        private String userId;

        public Mention(String tag, String userId) {
            this.id = String.valueOf(UUID.randomUUID());
            this.tag = tag;
            this.userId = userId;
        }
    }
}
