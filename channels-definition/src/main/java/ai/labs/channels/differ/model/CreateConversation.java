package ai.labs.channels.differ.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public class CreateConversation {
    private String conversationName;
    private String botUserIdCreator;
    private List<String> participantIds;
}
