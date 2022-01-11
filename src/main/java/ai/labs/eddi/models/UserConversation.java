package ai.labs.eddi.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserConversation {
    private String intent;
    private String userId;
    private Deployment.Environment environment;
    private String botId;
    private String conversationId;
}
