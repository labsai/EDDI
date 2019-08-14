package ai.labs.channels.differ.model.events;

import lombok.*;

import java.io.Serializable;
import java.util.Date;
import java.util.UUID;

@Getter
@Setter
@ToString
@EqualsAndHashCode
public abstract class Event implements Serializable {
    protected String eventId;
    protected String eventName;
    protected Date createdAt;
    protected String version;

    public abstract Payload getPayload();

    @Getter
    @Setter
    public static class Payload implements Serializable {
        protected Object error;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Part implements Serializable {
        private String id;
        private String body;
        private String mimeType;
        private String type;

        public Part(String body, String mimeType, String type) {
            this.id = String.valueOf(UUID.randomUUID());
            this.body = body;
            this.mimeType = mimeType;
            this.type = type;
        }
    }
}
