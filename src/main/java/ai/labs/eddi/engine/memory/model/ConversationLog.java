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
public class ConversationLog {
    private List<ConversationPart> messages = new LinkedList<>();

    @Override
    public String toString() {
        return messages.
                stream().map(part -> part.getRole() + ": " + part.getContent()).
                collect(Collectors.joining("\n"));
    }

    public Object toObject() {
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
        private List<Content> content;

        @Getter
        @Setter
        @NoArgsConstructor
        @AllArgsConstructor
        @EqualsAndHashCode
        @ToString
        public static class Content {
            private ContentType type;
            private String value;
        }

        public enum ContentType {
            text,
            image,
            pdf,
            audio,
            video
        }
    }
}
