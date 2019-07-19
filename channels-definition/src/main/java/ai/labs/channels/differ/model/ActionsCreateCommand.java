package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class ActionsCreateCommand extends Command {
    private Payload payload;

    public ActionsCreateCommand(AuthContext authContext, String commandId, String commandName, Date createdAt, Payload payload) {
        super(authContext, commandId, commandName, createdAt);
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
        }
    }

}
