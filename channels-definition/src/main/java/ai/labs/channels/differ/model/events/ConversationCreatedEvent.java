package ai.labs.channels.differ.model.events;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class ConversationCreatedEvent extends Event implements Serializable {
    private ConversationCreatedPayload payload;

    @Getter
    @Setter
    public static class ConversationCreatedPayload extends Payload implements Serializable {
        private String communityId;
        private Conversation conversation;
        private List<String> participantIds;
    }

    @Getter
    @Setter
    public static class Conversation implements Serializable {
        private String id;
        private List<String> participantIds;
        private String type;
    }
}
