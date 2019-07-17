package ai.labs.channels.differ.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class MessageCreateCommand extends Command {
    private String conversationId;
    private String inputType;
    private List<Event.Part> parts;
    private String senderId;

    public MessageCreateCommand(String id, String conversationId, String inputType, List<Event.Part> parts, String senderId) {
        super(id);
        this.conversationId = conversationId;
        this.inputType = inputType;
        this.parts = parts;
        this.senderId = senderId;
    }
}
