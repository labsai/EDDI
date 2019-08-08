package ai.labs.channels.differ.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CreateConversation {
    private String conversationName;
    private String botUserIdCreator;
    private List<String> participantIds;
}
