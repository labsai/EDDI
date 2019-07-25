package ai.labs.channels.differ.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class MessageCreateCommand extends Command {
    private Payload payload;

    public MessageCreateCommand(AuthContext authContext, String commandId, String commandName, Payload payload) {
        super(authContext, commandId, commandName);
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

        public Payload(String id, String conversationId, String senderId, String inputType, List<Event.Part> parts) {
            this.id = id;
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.inputType = inputType;
            this.parts = parts;
        }
    }
}
