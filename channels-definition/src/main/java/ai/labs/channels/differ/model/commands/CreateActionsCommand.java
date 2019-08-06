package ai.labs.channels.differ.model.commands;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class CreateActionsCommand extends Command {
    private Payload payload;

    public CreateActionsCommand(AuthContext authContext, String commandName, Date createdAt, Payload payload) {
        super(authContext, commandName, createdAt);
        this.payload = payload;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class Payload implements Serializable {
        private List<Action> actions;

        @Getter
        @Setter
        @AllArgsConstructor
        public static class Action implements Serializable {
            private String id;
            private String conversationId;
            private String messageId;
            private Boolean primary;
            private String text;

            public Action(String conversationId, String messageId, Boolean primary, String text) {
                this.id = String.valueOf(UUID.randomUUID());
                this.conversationId = conversationId;
                this.messageId = messageId;
                this.primary = primary;
                this.text = text;
            }
        }
    }

}
