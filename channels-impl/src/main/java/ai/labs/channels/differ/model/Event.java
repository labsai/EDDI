package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
public class Event {
    private String eventId;
    private String eventName;
    private Payload payload;
    private Message message;
    private Object error;
    private Date createdAt;
    private String version;

    @Getter
    @Setter
    public static class Payload {
        private String communityId;
        private Conversation conversation;
        private List<String> participantIds;
    }

    @Getter
    @Setter
    public static class Conversation {
        private String id;
        private List<String> participantIds;
        private String type;
    }

    @Getter
    @Setter
    public static class Message {
        private String id;
        private String conversationId;
        private String inputType;
        private List<String> mentions;
        private List<Part> parts;
        private String senderId;
        private Date sentAt;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Part {
        private String id;
        private String body;
        private String mimeType;
        private String type;
    }
}
