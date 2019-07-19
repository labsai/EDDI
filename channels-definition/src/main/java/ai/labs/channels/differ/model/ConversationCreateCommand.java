package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class ConversationCreateCommand extends Command {
    private Payload payload;

    public ConversationCreateCommand(AuthContext authContext, String commandId, String commandName, Date createdAt) {
        super(authContext, commandId, commandName, createdAt);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class Payload implements Serializable {
        private String id;
        private String name;
        private List<String> participantIds;
    }
}
