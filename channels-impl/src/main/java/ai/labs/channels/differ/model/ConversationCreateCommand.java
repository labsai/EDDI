package ai.labs.channels.differ.model;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
public class ConversationCreateCommand {
    private AuthContext authContext;
    private String commandId;
    private String commandName;
    private Date createdAt;
    private Payload payload;

    @Getter
    @Setter
    private class AuthContext {
        private String userId;
    }

    @Getter
    @Setter
    private static class Payload {
        private String id;
        private String name;
        private List<String> participantIds;
    }
}
