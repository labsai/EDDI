package ai.labs.channels.differ;

import ai.labs.channels.differ.model.CommandInfo;
import ai.labs.channels.differ.model.commands.CreateActionsCommand;
import ai.labs.channels.differ.model.commands.CreateMessageCommand;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.output.model.QuickReply;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class DifferOutputTransformerTest {

    private IDifferOutputTransformer differOutputTransformer;

    @Before
    public void setUp() {
        differOutputTransformer = new DifferOutputTransformer();
    }

    @Test
    public void convertBotOutputToMessageCreateCommands() {
        //setup
        var conversationOutput = new ConversationOutput();
        var firstMessage = "Hello!";
        var secondMessage = "This is a test";
        conversationOutput.put("output", Arrays.asList(firstMessage, secondMessage));
        String firstQR = "QR 1";
        String secondQR = "QR 2";
        var quickReplies = Arrays.asList(
                new QuickReply(firstQR, "exp(qr_1)", true),
                new QuickReply(secondQR, "exp(qr_2)", false));
        conversationOutput.put("quickReplies", quickReplies);
        var conversationOutputs = Collections.singletonList(conversationOutput);
        var botUserId = "botUserId";
        var conversationId = "conversationId";
        long timeOfLastMessageReceived = System.currentTimeMillis();

        //test
        List<CommandInfo> commandInfos = differOutputTransformer.convertBotOutputToMessageCreateCommands(
                conversationOutputs, botUserId, conversationId, timeOfLastMessageReceived);

        //assert
        assertEquals(3, commandInfos.size());
        var command_1 = (CreateMessageCommand) commandInfos.get(0).getCommand();
        var command_2 = (CreateMessageCommand) commandInfos.get(1).getCommand();
        var command_3 = (CreateActionsCommand) commandInfos.get(2).getCommand();

        assertEquals(botUserId, command_1.getAuthContext().getUserId());
        assertEquals(conversationId, command_1.getPayload().getConversationId());
        assertEquals(firstMessage, command_1.getPayload().getParts().get(0).getBody());

        assertEquals(botUserId, command_2.getAuthContext().getUserId());
        assertEquals(conversationId, command_2.getPayload().getConversationId());
        assertEquals(secondMessage, command_2.getPayload().getParts().get(0).getBody());

        var actions = command_3.getPayload().getActions();
        assertEquals(2, actions.size());
        assertEquals(firstQR, actions.get(0).getText());
        assertEquals(conversationId, actions.get(1).getConversationId());
    }
}