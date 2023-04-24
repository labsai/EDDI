package ai.labs.eddi.engine.memory.model;

import lombok.*;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class ConversationLog {
    private List<ConversationPart> messages = new LinkedList<>();

    public String getConversationLogAsString() {
        return messages.
                stream().map(part -> part.getRole() + ": " + part.getContent()).
                collect(Collectors.joining("\n"));
    }

    public Object getConversationLogAsObject() {
        return messages;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    public static class ConversationPart {
        private String role;
        private String content;
    }
}
