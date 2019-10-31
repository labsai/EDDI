package ai.labs.channels.xmpp;

import ai.labs.channels.config.model.ChannelDefinition;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.models.InputData;
import ai.labs.rest.MockAsyncResponse;
import ai.labs.rest.restinterfaces.IRestBotManagement;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.ChatManager;
import org.jivesoftware.smack.chat2.IncomingChatMessageListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jxmpp.jid.EntityBareJid;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

@Slf4j
@Singleton
public class XmppEndpoint implements IXmppEndpoint, IncomingChatMessageListener {
    private static final String BOT_INTENT = "xmpp";
    private final IRestBotManagement restBotManagement;

    private XMPPTCPConnection connection;

    @Inject
    public XmppEndpoint(IRestBotManagement restBotManagement) {
        this.restBotManagement = restBotManagement;
    }

    public void init(ChannelDefinition channelDefinition) {
        try {

            Map<String, Object> channelConnectorConfig = channelDefinition.getConfig();
            Object usernameObj;
            String username = null;
            if (!isNullOrEmpty((usernameObj = channelConnectorConfig.get("username")))) {
                username = usernameObj.toString();
            }

            Object passwordObj;
            String password = null;
            if (!isNullOrEmpty((passwordObj = channelConnectorConfig.get("password")))) {
                password = passwordObj.toString();
            }

            Object hostnameObj;
            String hostname;
            if (!isNullOrEmpty((hostnameObj = channelConnectorConfig.get("hostname")))) {
                hostname = hostnameObj.toString();
            } else {
                log.error("xmpp hostname cannot be null or empty");
                return;
            }

            log.info("opening connection to: {}", hostname);
            var builder = XMPPTCPConnectionConfiguration.builder();
            if (username != null && password != null) {
                builder = builder.setUsernameAndPassword(username, password);
            }

            builder.setHost(hostname)
                    .setHostAddress(InetAddress.getByName(hostname))
                    .setXmppDomain(hostname)
                    .setPort(5222)
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
                    .setDebuggerEnabled(false);

            connection = new XMPPTCPConnection(builder.build());
            connection.connect().login();
            ChatManager chatManager = ChatManager.getInstanceFor(connection);
            chatManager.addIncomingListener(this);

            log.info("XMPP STARTING");

        } catch (InterruptedException e) {
            log.error("smack connection interrupted", e);
        } catch (IOException e) {
            log.error("smack io exception", e);
        } catch (SmackException e) {
            log.error("smack exception", e);
        } catch (XMPPException e) {
            log.error("xmpp exception", e);
        }
    }

    private void say(String senderId, String message, Chat chat) {
        InputData inputData = new InputData(message, new HashMap<>());

        restBotManagement.sayWithinContext(BOT_INTENT, senderId,
                false, true, null, inputData,
                new MockAsyncResponse() {
                    @Override
                    public boolean resume(Object response) {
                        try {
                            SimpleConversationMemorySnapshot memorySnapshot = (SimpleConversationMemorySnapshot) response;

                            for (ConversationOutput conversationOutput : memorySnapshot.getConversationOutputs()) {
                                List outputs = conversationOutput.get("output", List.class);
                                for (Object output : outputs) {
                                    chat.send(output.toString());

                                }
                            }

                            return true;
                        } catch (SmackException.NotConnectedException e) {
                            log.error("not connected to xmpp server", e);
                        } catch (InterruptedException e) {
                            log.error("interrupted exception", e);
                        }

                        return true;
                    }
                });
    }

    @Override
    public void newIncomingMessage(EntityBareJid entityBareJid, Message message, Chat chat) {
        log.info("xmpp incoming: {}", message.getBody());
        String senderId = entityBareJid.asEntityBareJidString();
        say(senderId, message.getBody(), chat);
    }
}
