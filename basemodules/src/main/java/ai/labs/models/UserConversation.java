package ai.labs.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class UserConversation {
    private String intent;
    private String userId;
    private Deployment.Environment environment;
    private String botId;
    private String conversationId;
}
