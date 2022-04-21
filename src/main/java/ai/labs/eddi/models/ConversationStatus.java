package ai.labs.eddi.models;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * @author ginccc
 */
@Getter
@Setter
public class ConversationStatus {
    private String conversationId;
    private String botId;
    private Integer botVersion;
    private ConversationState conversationState;
    private Date lastInteraction;
}
