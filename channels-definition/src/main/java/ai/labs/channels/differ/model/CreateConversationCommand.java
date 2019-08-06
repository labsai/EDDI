package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class CreateConversationCommand extends Command {
    private Payload payload;

    public CreateConversationCommand(AuthContext authContext, String commandName, Date createdAt) {
        super(authContext, commandName, createdAt);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class Payload implements Serializable {
        private String id;
        private String name;
        private List<String> participantIds;

        public Payload(String name, List<String> participantIds) {
            this.id = String.valueOf(UUID.randomUUID());
            this.name = name;
            this.participantIds = participantIds;
        }
    }
}
