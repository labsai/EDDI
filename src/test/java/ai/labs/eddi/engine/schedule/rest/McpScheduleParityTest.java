/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.rest;

import ai.labs.eddi.configs.agents.AgentCascadeService;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.agenttests.AgentTestConversations;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.mcp.McpAdminTools;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.RagIngestionSchedules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The MCP schedule tools and {@code /schedulestore/schedules} answer the same
 * inputs the same way, because the tools run on the real
 * {@link RestScheduleStore} under the MCP caller's identity.
 * <p>
 * They used to address {@link IScheduleStore} directly and copy the parts of
 * the REST logic someone remembered: {@code create_schedule} skipped validation
 * (an unknown conversation strategy was stored, a bad time zone answered an
 * internal error) and never stamped the creator, so the fire-time re-check of
 * the creator's access was skipped for good; {@code fire_schedule_now} refused
 * only HITL timeouts and continuations, firing ingestion, email-poll,
 * team-cadence and lesson schedules without the EDIT check REST applies to
 * them.
 * <p>
 * The REST store is wired with mocks below its own seams (the schedule store,
 * the poller, the executor, the access guard), so every assertion here is about
 * the code path, not about a stub of it. The test lives in the REST package to
 * reach those seams without reflection.
 */
class McpScheduleParityTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SchedulePollerService pollerService;
    private ResourceAccessGuard accessGuard;
    private RestScheduleStore rest;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);
        pollerService = mock(SchedulePollerService.class);
        lenient().when(pollerService.claimForManualFire(any())).thenReturn(true);
        accessGuard = mock(ResourceAccessGuard.class);
        lenient().when(accessGuard.seesEverything()).thenReturn(true);
        lenient().when(accessGuard.hasAccess(any(), any())).thenReturn(true);
        lenient().when(accessGuard.callerIsAdmin()).thenReturn(true);
        lenient().when(accessGuard.currentTeamSubjects()).thenReturn(List.of("team:ops"));

        SecurityIdentity admin = caller("admin-alice", true);
        rest = restStore(admin);
        tools = mcpTools(rest, admin, true);
    }

    @Test
    @DisplayName("create_schedule stamps the creator from the caller, as REST does")
    void createStampsTheCreator() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("s-new");

        JsonNode result = json(tools.createSchedule("agent-1", "CRON", "0 9 * * *", null, "Good morning", "Morning", null, null, null, null));

        assertEquals("schedule_created", result.path("action").asText(), result.toString());
        ArgumentCaptor<ScheduleConfiguration> stored = ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(stored.capture());
        // Without createdBy, ScheduleFireExecutor's fire-time check of the creator's
        // access to the agent is skipped for every fire of this schedule.
        assertEquals("admin-alice", stored.getValue().getCreatedBy());
        assertEquals(List.of("team:ops"), stored.getValue().getCreatorTeams());
        assertTrue(stored.getValue().isCreatorAdmin());
        verify(accessGuard).requireAgentUseAccess("agent-1");
    }

    static Stream<Arguments> refusedSchedules() {
        return Stream.of(
                Arguments.of("an unknown conversation strategy", null, "0 9 * * *", null, "hi", null, "sometimes", null),
                Arguments.of("an unknown time zone", null, "0 9 * * *", null, "hi", "Mars/Base", null, null),
                Arguments.of("a cron that never matches", null, "0 0 30 2 *", null, "hi", null, null, null),
                Arguments.of("a heartbeat below the floor", "HEARTBEAT", null, 30L, null, null, null, null),
                Arguments.of("a malformed cron", null, "every morning", null, "hi", null, null, null),
                Arguments.of("a cron without a message", null, "0 9 * * *", null, null, null, null, null),
                Arguments.of("a userId reserved for agent tests", null, "0 9 * * *", null, "hi", null, null,
                        AgentTestConversations.USER_ID_PREFIX + "run-1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedSchedules")
    @DisplayName("create_schedule refuses what REST refuses, with REST's answer, and stores nothing")
    void createRefusesWhatRestRefuses(String label, String triggerType, String cron, Long heartbeat, String message, String timeZone,
                                      String strategy, String userId)
            throws Exception {
        var body = new ScheduleConfiguration();
        body.setName("parity");
        body.setAgentId("agent-1");
        if (triggerType != null) {
            body.setTriggerType(TriggerType.valueOf(triggerType));
        }
        body.setCronExpression(cron);
        body.setHeartbeatIntervalSeconds(heartbeat);
        body.setMessage(message);
        body.setTimeZone(timeZone);
        body.setConversationStrategy(strategy);
        body.setUserId(userId);
        Response restAnswer = rest.createSchedule(body);
        assertEquals(400, restAnswer.getStatus(), label);
        Object restMessage = ((Map<?, ?>) restAnswer.getEntity()).get("message");

        JsonNode mcpAnswer = json(tools.createSchedule("agent-1", triggerType, cron, heartbeat, message, "parity", timeZone, strategy, userId, null));

        assertEquals(restMessage, mcpAnswer.path("error").asText(), label);
        assertEquals("invalid_schedule", mcpAnswer.path("errorCode").asText(), label);
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    @DisplayName("fire_schedule_now applies REST's EDIT check on the knowledge base an ingestion schedule crawls")
    void fireChecksTheKnowledgeBase() throws Exception {
        when(scheduleStore.readSchedule("ing-1")).thenReturn(ingestionSchedule("ing-1", "kb-1"));
        doThrow(new ForbiddenException("Access denied: you do not have edit access to this RAG configuration")).when(accessGuard)
                .requireAccess("kb-1", AccessLevel.EDIT, "RAG configuration");

        JsonNode result = json(tools.fireScheduleNow("ing-1"));

        assertEquals("FORBIDDEN", result.path("errorCode").asText(), result.toString());
        assertTrue(result.path("error").asText().contains("edit access to this RAG configuration"), result.toString());
        verify(pollerService, never()).claimForManualFire(any());
        // any(), not anyString(): the mocked poller's instance id is null, and
        // anyString() never matches null, so it would pass with the fire made.
        verify(fireExecutor, never()).fire(any(), any(), anyInt());
    }

    @Test
    @DisplayName("fire_schedule_now refuses an ingestion schedule that names no knowledge base, with REST's text")
    void fireRefusesAMalformedIngestionMarker() throws Exception {
        when(scheduleStore.readSchedule("ing-2")).thenReturn(ingestionSchedule("ing-2", null));

        JsonNode result = json(tools.fireScheduleNow("ing-2"));

        assertEquals("BAD_REQUEST", result.path("errorCode").asText(), result.toString());
        assertEquals("This ingestion schedule names no knowledge base and cannot be fired.", result.path("error").asText());
        verify(pollerService, never()).claimForManualFire(any());
    }

    /**
     * The tools are admin-only today, and an administrator may read every schedule,
     * so this is not reachable through a deployed server. It pins that the answer
     * comes from REST's owner rule applied to the caller's identity, not from a
     * read of the store: with the role check off (authorization disabled at the
     * tool), an editor's identity reaching REST is refused another user's schedule.
     */
    @Test
    @DisplayName("read_schedule answers with REST's owner rule for the caller's identity")
    void readAppliesTheOwnerRule() throws Exception {
        SecurityIdentity editor = caller("editor-bob", false);
        RestScheduleStore editorRest = restStore(editor);
        McpAdminTools editorTools = mcpTools(editorRest, editor, false);
        var alices = new ScheduleConfiguration();
        alices.setId("s-alice");
        alices.setName("Alice's dream");
        alices.setAgentId("agent-1");
        alices.setUserId("alice");
        alices.setCronExpression("0 3 * * *");
        when(scheduleStore.readSchedule("s-alice")).thenReturn(alices);

        JsonNode result = json(editorTools.readSchedule("s-alice"));

        assertEquals("FORBIDDEN", result.path("errorCode").asText(), result.toString());
        verify(scheduleStore, never()).readFireLogs(anyString(), anyInt());
    }

    private RestScheduleStore restStore(SecurityIdentity identity) {
        var store = new RestScheduleStore();
        store.scheduleStore = scheduleStore;
        store.fireExecutor = fireExecutor;
        store.pollerService = pollerService;
        store.identity = identity;
        store.ownershipValidator = new OwnershipValidator(true);
        store.resourceAccessGuard = accessGuard;
        store.defaultTimeZone = "UTC";
        store.minIntervalSeconds = 60;
        return store;
    }

    private McpAdminTools mcpTools(RestScheduleStore store, SecurityIdentity identity, boolean authEnabled) {
        return new McpAdminTools(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class), new JsonSerialization(mapper),
                mock(StrictConfigurationParser.class), store, identity, authEnabled,
                mock(AgentCascadeService.class));
    }

    private static SecurityIdentity caller(String name, boolean admin) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = () -> name;
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        lenient().when(identity.hasRole("eddi-admin")).thenReturn(admin);
        return identity;
    }

    private static ScheduleConfiguration ingestionSchedule(String id, String ragConfigId) {
        var schedule = new ScheduleConfiguration();
        schedule.setId(id);
        schedule.setName("ingest " + id);
        schedule.setAgentId("agent-1");
        schedule.setCronExpression("0 2 * * *");
        schedule.setMessage("ingest");
        schedule.setMetadata(ragConfigId == null
                ? Map.of(RagIngestionSchedules.METADATA_TYPE_KEY, true)
                : Map.of(RagIngestionSchedules.METADATA_TYPE_KEY, true, RagIngestionSchedules.METADATA_RAG_CONFIG_ID, ragConfigId));
        return schedule;
    }

    private JsonNode json(String result) throws Exception {
        return mapper.readTree(result);
    }
}
