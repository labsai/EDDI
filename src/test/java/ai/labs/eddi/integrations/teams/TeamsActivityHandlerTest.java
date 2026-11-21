/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IDeploymentStatusReader;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.integrations.channels.ChannelConnectionCredentials;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChatThreadConversations;
import ai.labs.eddi.integrations.teams.refs.ITeamsConversationReferenceStore;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class TeamsActivityHandlerTest {

    static final String APP = "11111111-2222-3333-4444-555555555555";
    static final String OTHER_APP = "22222222-2222-3333-4444-555555555555";
    static final String TENANT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String FOREIGN_TENANT = "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String USER = "33333333-2222-3333-4444-555555555555";
    static final String SERVICE_URL = "https://smba.trafficmanager.net/emea/";
    static final String CHANNEL = "19:general@thread.tacv2";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IChannelIntegrationStore channelStore;
    private final List<DocumentDescriptor> descriptors = new ArrayList<>();
    private int nextId;
    private ChannelTargetRouter router;
    private ChannelConnectionCredentials credentials;
    private ChatThreadConversations threads;
    private IConversationService conversationService;
    private TeamsConnectorClient connector;
    private ITeamsConversationReferenceStore references;
    private TeamsActivityHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        channelStore = mock(IChannelIntegrationStore.class);
        IDocumentDescriptorStore descriptorStore = mock(IDocumentDescriptorStore.class);
        when(descriptorStore.readDescriptors(eq("ai.labs.channel"), anyString(), anyInt(), anyInt(), anyBoolean())).thenReturn(descriptors);
        IDeploymentStatusReader agentAdmin = mock(IDeploymentStatusReader.class);
        when(agentAdmin.readAllDeploymentStatuses(any())).thenReturn(List.of());
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        ICache<String, ChannelTarget> locks = mock(ICache.class);
        Map<String, ChannelTarget> lockMap = new ConcurrentHashMap<>();
        when(locks.get(any())).thenAnswer(inv -> lockMap.get(inv.getArgument(0)));
        when(locks.put(any(), any())).thenAnswer(inv -> lockMap.put(inv.getArgument(0), inv.getArgument(1)));
        when(cacheFactory.<String, ChannelTarget>getCache(anyString(), any(Duration.class))).thenReturn(locks);
        router = new ChannelTargetRouter(channelStore, descriptorStore, agentAdmin, mock(IAgentStore.class), mock(SecretResolver.class), null,
                cacheFactory);

        credentials = mock(ChannelConnectionCredentials.class);
        when(credentials.holdsRequiredIdentity(any(), any())).thenReturn(true);
        threads = mock(ChatThreadConversations.class);
        when(threads.open(anyString(), anyString(), anyString())).thenReturn(new ChatThreadConversations.ThreadConversation("conv-1", false, null));
        conversationService = mock(IConversationService.class);
        doAnswer(inv -> {
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output", List.of("agent says hi"));
            snapshot.setConversationOutputs(List.of(output));
            inv.getArgument(6, IConversationService.ConversationResponseHandler.class).onComplete(snapshot);
            return null;
        }).when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        connector = mock(TeamsConnectorClient.class);
        references = mock(ITeamsConversationReferenceStore.class);
        handler = new TeamsActivityHandler(router, new TeamsChannelType(), credentials, threads, conversationService, connector, references, 5);
    }

    private ChannelIntegrationConfiguration integration(String name, String appId, String conversationId, String agentId) throws Exception {
        String id = String.format("5262b802dc6c4008b5%02x", nextId++);
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.channel/channelstore/channels/" + id + "?version=1"));
        descriptors.add(descriptor);
        var config = new ChannelIntegrationConfiguration();
        config.setName(name);
        config.setChannelType("teams");
        config.setDefaultTargetName("assistant");
        var target = new ChannelTarget();
        target.setName("assistant");
        target.setTargetId(agentId);
        target.setType(ChannelTarget.TargetType.AGENT);
        config.setTargets(List.of(target));
        Map<String, String> pc = new HashMap<>();
        pc.put("appId", appId);
        pc.put("tenantId", TENANT);
        pc.put("connection", "teams-bot");
        if (conversationId != null) {
            pc.put("conversationId", conversationId);
        }
        config.setPlatformConfig(pc);
        when(channelStore.read(id, 1)).thenReturn(config);
        return config;
    }

    private static JsonNode message(String conversationId, String conversationType, String tenant, String text) throws Exception {
        Map<String, Object> activity = new HashMap<>();
        activity.put("type", "message");
        activity.put("id", "act-1");
        activity.put("channelId", "msteams");
        activity.put("serviceUrl", SERVICE_URL);
        activity.put("text", text);
        activity.put("from", Map.of("id", "29:user", "aadObjectId", USER));
        activity.put("recipient", Map.of("id", "28:" + APP, "name", "Bot"));
        activity.put("conversation", Map.of("id", conversationId, "conversationType", conversationType, "tenantId", tenant));
        activity.put("channelData", Map.of("tenant", Map.of("id", tenant)));
        activity.put("entities", List.of(Map.of("type", "mention", "text", "<at>Bot</at>", "mentioned", Map.of("id", "28:" + APP))));
        return MAPPER.valueToTree(activity);
    }

    @Test
    @DisplayName("a personal chat reaches the app's channel-less integration, with a teams: identity")
    void personalChat() throws Exception {
        integration("dm", APP, null, "agent-dm");
        handler.handle(APP, message("a:personal-1", "personal", TENANT, "hello"));
        verify(threads).open(eq("agent-dm"), eq("teams:" + TENANT + ":" + USER), anyString());
        verify(connector).sendText(any(), eq(SERVICE_URL), eq("a:personal-1"), eq("act-1"), eq("agent says hi"));
        verify(references).upsert(any());
    }

    @Test
    @DisplayName("a channel thread goes to the integration bound to the channel, the bot mention stripped")
    void channelThread() throws Exception {
        integration("dm", APP, null, "agent-dm");
        integration("general", APP, CHANNEL, "agent-general");
        doAnswer(inv -> {
            assertEquals("what's up", inv.getArgument(4, InputData.class).getInput());
            var snapshot = new SimpleConversationMemorySnapshot();
            inv.getArgument(6, IConversationService.ConversationResponseHandler.class).onComplete(snapshot);
            return null;
        }).when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        handler.handle(APP, message(CHANNEL + ";messageid=1700000000000", "channel", TENANT, "<at>Bot</at> what's up"));
        verify(threads).open(eq("agent-general"), anyString(), anyString());
    }

    @Test
    @DisplayName("a tenant the integration does not accept is dropped, before anything is stored or answered")
    void foreignTenant() throws Exception {
        integration("dm", APP, null, "agent-dm");
        handler.handle(APP, message("a:personal-1", "personal", FOREIGN_TENANT, "hello"));
        verify(threads, never()).open(anyString(), anyString(), anyString());
        verify(connector, never()).sendText(any(), any(), any(), any(), any());
        verify(references, never()).upsert(any());
    }

    @Test
    @DisplayName("a missing tenant is dropped: fail closed")
    void missingTenant() throws Exception {
        integration("dm", APP, null, "agent-dm");
        var activity = (ObjectNode) message("a:personal-1", "personal", TENANT, "hello");
        activity.remove("channelData");
        ((ObjectNode) activity.get("conversation")).remove("tenantId");
        handler.handle(APP, activity);
        verify(connector, never()).sendText(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a channel bound to another app's integration is not answered by this app")
    void channelOfAnotherApp() throws Exception {
        integration("general-other", OTHER_APP, CHANNEL, "agent-other");
        handler.handle(APP, message(CHANNEL, "channel", TENANT, "<at>Bot</at> hi"));
        verify(threads, never()).open(eq("agent-other"), anyString(), anyString());
    }

    @Test
    @DisplayName("an integration whose connection is not the app's own is ignored")
    void connectionNotTheAppsOwn() throws Exception {
        integration("dm", APP, null, "agent-dm");
        when(credentials.holdsRequiredIdentity(any(), any())).thenReturn(false);
        handler.handle(APP, message("a:personal-1", "personal", TENANT, "hello"));
        verify(threads, never()).open(anyString(), anyString(), anyString());
        verify(connector, never()).sendText(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("an app no integration declares has nothing to answer")
    void unknownApp() throws Exception {
        integration("dm", APP, null, "agent-dm");
        assertTrue(handler.isKnownApp(APP));
        assertFalse(handler.isKnownApp(OTHER_APP));
        handler.handle(OTHER_APP, message("a:personal-1", "personal", TENANT, "hello"));
        verify(threads, never()).open(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the bot's own messages are ignored")
    void ownMessage() throws Exception {
        integration("dm", APP, null, "agent-dm");
        var activity = (ObjectNode) message("a:personal-1", "personal", TENANT, "hello");
        activity.set("from", MAPPER.valueToTree(Map.of("id", "28:" + APP)));
        handler.handle(APP, activity);
        verify(connector, never()).sendText(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a mention-only message gets the help text")
    void mentionOnly() throws Exception {
        integration("dm", APP, null, "agent-dm");
        handler.handle(APP, message("a:personal-1", "personal", TENANT, "<at>Bot</at>"));
        verify(connector).sendText(any(), eq(SERVICE_URL), eq("a:personal-1"), eq("act-1"), eq(TeamsActivityHandler.HELP_TEXT));
    }

    @Test
    @DisplayName("being added to a conversation posts the welcome")
    void welcome() throws Exception {
        integration("dm", APP, null, "agent-dm");
        Map<String, Object> activity = new HashMap<>();
        activity.put("type", "conversationUpdate");
        activity.put("serviceUrl", SERVICE_URL);
        activity.put("recipient", Map.of("id", "28:" + APP));
        activity.put("conversation", Map.of("id", "a:personal-1", "conversationType", "personal"));
        activity.put("channelData", Map.of("tenant", Map.of("id", TENANT)));
        activity.put("membersAdded", List.of(Map.of("id", "28:" + APP)));
        handler.handle(APP, MAPPER.valueToTree(activity));
        verify(connector).sendText(any(), eq(SERVICE_URL), eq("a:personal-1"), any(), eq(TeamsActivityHandler.WELCOME_TEXT));
    }

    @Test
    void onlyTheBotsMentionIsStripped() throws Exception {
        var activity = MAPPER.valueToTree(Map.of("entities", List.of(Map.of("type", "mention", "text", "<at>Bot</at>", "mentioned", Map.of(
                "id", "28:bot")), Map.of("type", "mention", "text", "<at>Ann</at>", "mentioned", Map.of("id", "29:ann")))));
        assertEquals("ask <at>Ann</at>", TeamsActivityHandler.stripBotMentions("<at>Bot</at> ask <at>Ann</at>", (JsonNode) activity,
                "28:bot"));
    }
}
