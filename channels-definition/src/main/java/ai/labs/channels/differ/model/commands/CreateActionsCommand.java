package ai.labs.channels.differ.model.commands;

import lombok.*;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class CreateActionsCommand extends Command {
    public static final String CREATE_ACTIONS_EXCHANGE = "message-actions";
    public static final String CREATE_ACTIONS_ROUTING_KEY = CREATE_ACTIONS_EXCHANGE + ".createMany";
    private Payload payload;

    public CreateActionsCommand(AuthContext authContext, Date createdAt, Payload payload) {
        super(authContext, CREATE_ACTIONS_ROUTING_KEY, createdAt);
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
