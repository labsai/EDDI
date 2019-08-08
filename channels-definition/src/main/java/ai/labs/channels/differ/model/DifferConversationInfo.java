package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class DifferConversationInfo {
    private String conversationId;
    private List<String> allParticipantIds;
    private List<String> botParticipantIds;
}
