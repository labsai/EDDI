package ai.labs.channels.differ.model;

import lombok.*;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@ToString
@EqualsAndHashCode
public class DifferConversationInfo {
    private String conversationId;
    private List<String> allParticipantIds;
    private List<String> botParticipantIds;
}
