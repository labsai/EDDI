package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@AllArgsConstructor
@Getter
@Setter
public class ConversationInfo {
    private String conversationId;
    private List<String> allParticipantIds;
    private List<String> botParticipantIds;
}
