package ai.labs.channels.differ.model.commands;

import ai.labs.channels.differ.model.events.Event;
import lombok.*;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class CreateMessageCommand extends Command {
    public static final String CREATE_MESSAGE_ROUTING_KEY = "message.create";
    private Payload payload;

    public CreateMessageCommand(AuthContext authContext, Payload payload) {
        super(authContext, CREATE_MESSAGE_ROUTING_KEY);
        this.payload = payload;
    }

    @Setter
    @Getter
    @NoArgsConstructor
    public static class Payload implements Serializable {
        private String id;
        private String conversationId;
        private String senderId;
        private Date sentAt;
        private String inputType;
        private List<Event.Part> parts;

        public Payload(String conversationId, String senderId, String inputType, List<Event.Part> parts) {
            this.id = String.valueOf(UUID.randomUUID());
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.inputType = inputType;
            this.parts = parts;
        }
    }
}
