/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.schedule.rest;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.FireStatus;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration.TriggerType;
import ai.labs.eddi.engine.schedule.model.ScheduleFireLog;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestScheduleStore}.
 */
class RestScheduleStoreTest {

    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor fireExecutor;
    private SchedulePollerService pollerService;
    private SecurityIdentity identity;
    private RestScheduleStore rest;

    @BeforeEach
    void setUp() {
        scheduleStore = mock(IScheduleStore.class);
        fireExecutor = mock(ScheduleFireExecutor.class);
        pollerService = mock(SchedulePollerService.class);
        identity = mock(SecurityIdentity.class);
        // A manual fire claims the schedule first, exactly as the poller does, so it
        // cannot run concurrently with the poller's own fire of the same schedule.
        // Default the claim to "won" so tests about anything else still reach the fire.
        when(pollerService.claimForManualFire(any())).thenReturn(true);

        rest = new RestScheduleStore();
        // Inject mocks (field injection in REST — we use reflection for tests)
        setField(rest, "scheduleStore", scheduleStore);
        setField(rest, "fireExecutor", fireExecutor);
        setField(rest, "pollerService", pollerService);
        setField(rest, "identity", identity);
        // Auth-enabled validator: isAdmin() is then driven by the mocked identity's
        // role.
        setField(rest, "ownershipValidator", new OwnershipValidator(true));
        setField(rest, "resourceAccessGuard", permissiveResourceGuard());
        setField(rest, "defaultTimeZone", "UTC");
        setField(rest, "minIntervalSeconds", 60L);
    }

    /** A HITL approval-timeout schedule as stored by ConversationService. */
    private static ScheduleConfiguration hitlSchedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("hitl-timeout-conv-" + id);
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setOneTimeAt(Instant.now().plusSeconds(3600).toString());
        s.setMetadata(Map.of("hitlType", "hitl_timeout", "policy", "AUTO_APPROVE",
                "surface", "regular", "conversationId", "conv-" + id));
        return s;
    }

    // --- Create ---

    @Test
    void create_cronSchedule_setsDefaults() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("new-id");

        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setCronExpression("0 9 * * *");
        schedule.setMessage("Good morning");
        schedule.setName("Morning check");

        Response response = rest.createSchedule(schedule);

        assertEquals(201, response.getStatus());
        var created = (ScheduleConfiguration) response.getEntity();
        assertEquals(TriggerType.CRON, created.getTriggerType());
        assertEquals("production", created.getEnvironment());
        assertEquals("system:scheduler", created.getUserId());
        assertEquals("new", created.getConversationStrategy());
        assertEquals("UTC", created.getTimeZone());
        assertNotNull(created.getNextFire());
    }

    @Test
    void create_heartbeatSchedule_setsDefaults() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("hb-id");

        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setHeartbeatIntervalSeconds(300L);
        schedule.setName("Health check");

        Response response = rest.createSchedule(schedule);

        assertEquals(201, response.getStatus());
        var created = (ScheduleConfiguration) response.getEntity();
        assertEquals(TriggerType.HEARTBEAT, created.getTriggerType());
        assertEquals("persistent", created.getConversationStrategy());
        assertEquals("heartbeat", created.getMessage());
        assertNotNull(created.getNextFire());
    }

    @Test
    void create_rejectsMissingAgentId() {
        var schedule = new ScheduleConfiguration();
        schedule.setCronExpression("0 9 * * *");
        schedule.setMessage("Hello");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsCronWithoutMessage() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setCronExpression("0 9 * * *");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsHeartbeatWithoutInterval() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setTriggerType(TriggerType.HEARTBEAT);
        schedule.setName("Bad heartbeat");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsIntervalBelowMinimum() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setTriggerType(TriggerType.HEARTBEAT);
        schedule.setHeartbeatIntervalSeconds(30L); // below 60s minimum
        schedule.setName("Too fast");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("Invalid schedule"));
    }

    @Test
    void create_rejectsInvalidCron() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setCronExpression("not valid");
        schedule.setMessage("Hello");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    @Test
    void create_rejectsInvalidTimezone() {
        var schedule = new ScheduleConfiguration();
        schedule.setAgentId("agent-1");
        schedule.setCronExpression("0 9 * * *");
        schedule.setMessage("Hello");
        schedule.setTimeZone("Invalid/Zone");

        Response response = rest.createSchedule(schedule);

        assertEquals(400, response.getStatus());
    }

    // --- Read ---

    @Test
    void readAll_delegatesToStore() throws Exception {
        when(scheduleStore.readAllSchedules(500, 0)).thenReturn(List.of());

        List<ScheduleConfiguration> result = rest.readAllSchedules(null, 500, 0);

        assertEquals(0, result.size());
        verify(scheduleStore).readAllSchedules(500, 0);
    }

    @Test
    void readAll_filtersByAgentId() throws Exception {
        when(scheduleStore.readSchedulesByAgentId("agent-1", 500, 0)).thenReturn(List.of());

        rest.readAllSchedules("agent-1", 500, 0);

        verify(scheduleStore).readSchedulesByAgentId("agent-1", 500, 0);
        verify(scheduleStore, never()).readAllSchedules(anyInt(), anyInt());
    }

    // --- Enable / Disable ---

    @Test
    void enable_callsAtomicSetEnabled() throws Exception {
        var schedule = makeCronSchedule("sched-1");
        when(scheduleStore.readSchedule("sched-1")).thenReturn(schedule);

        rest.enableSchedule("sched-1");

        verify(scheduleStore).setScheduleEnabled(eq("sched-1"), eq(true), any(Instant.class));
    }

    @Test
    void disable_callsAtomicSetEnabled() throws Exception {
        rest.disableSchedule("sched-1");

        verify(scheduleStore).setScheduleEnabled("sched-1", false, null);
    }

    // --- Helpers ---

    // --- Finding #5: HITL schedule bypass guards ---

    @Test
    void fireNow_hitlSchedule_refusedWithConflict_evenForAdmin() throws Exception {
        // An admin firing manually would STILL side-step the /resume audit path.
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.fireNow("h1");

        assertEquals(409, response.getStatus());
        // Must NOT invoke the fire executor's HITL fast-path.
        verify(fireExecutor, never()).fire(any(), any(), anyInt());
    }

    @Test
    void fireNow_hitlSchedule_refusedForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false); // plain editor
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.fireNow("h1");

        assertEquals(409, response.getStatus());
        verify(fireExecutor, never()).fire(any(), any(), anyInt());
    }

    @Test
    void fireNow_regularSchedule_stillFires() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        var regular = makeCronSchedule("r1");
        when(scheduleStore.readSchedule("r1")).thenReturn(regular);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(new ScheduleFireLog(
                        "log-1", "r1", "fire-1", null, Instant.now(), Instant.now(),
                        FireStatus.COMPLETED.name(), "n1", "conv-1", null, 1, 0.0));

        Response response = rest.fireNow("r1");

        assertEquals(200, response.getStatus());
        verify(fireExecutor).fire(any(), any(), anyInt());
        // setUp() stubs claimForManualFire to "won" so tests about anything else still
        // reach the executor. Assert it here, on the canonical happy path, so the claim
        // step is not invisible to every pre-existing fireNow test.
        verify(pollerService).claimForManualFire(regular);
    }

    @Test
    void deleteSchedule_hitl_forbiddenForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.deleteSchedule("h1");

        assertEquals(403, response.getStatus());
        verify(scheduleStore, never()).deleteSchedule("h1");
    }

    @Test
    void deleteSchedule_hitl_allowedForAdmin() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.deleteSchedule("h1");

        assertEquals(204, response.getStatus());
        verify(scheduleStore).deleteSchedule("h1");
    }

    @Test
    void disableSchedule_hitl_forbiddenForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        Response response = rest.disableSchedule("h1");

        assertEquals(403, response.getStatus());
        verify(scheduleStore, never()).setScheduleEnabled(eq("h1"), anyBoolean(), any());
    }

    @Test
    void updateSchedule_hitl_forbiddenForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(scheduleStore.readSchedule("h1")).thenReturn(hitlSchedule("h1"));

        // Body omits the metadata marker — the guard must still detect HITL via the
        // STORED schedule.
        var body = makeCronSchedule("h1");
        Response response = rest.updateSchedule("h1", body);

        assertEquals(403, response.getStatus());
        verify(scheduleStore, never()).updateSchedule(eq("h1"), any());
    }

    // --- G3: forging a HITL timeout schedule via create/update is denied for
    // EVERYONE (even admin). These schedules are minted internally only; a forged
    // one would let the poller force-resume/abort a victim's approval
    // unauthenticated.

    @Test
    void createSchedule_hitlBody_rejectedForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);

        Response response = rest.createSchedule(hitlSchedule("v1"));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    void createSchedule_hitlBody_rejectedForAdmin() throws Exception {
        // Even an admin cannot mint a HITL timeout schedule via REST.
        when(identity.hasRole("eddi-admin")).thenReturn(true);

        Response response = rest.createSchedule(hitlSchedule("v1"));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    void updateSchedule_convertBodyToHitl_rejectedForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        // Stored schedule is a plain (non-HITL) cron — the guard must catch the
        // hitl_timeout marker on the INCOMING body, closing the conversion path.
        when(scheduleStore.readSchedule("r1")).thenReturn(makeCronSchedule("r1"));

        Response response = rest.updateSchedule("r1", hitlSchedule("r1"));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).updateSchedule(eq("r1"), any());
    }

    @Test
    void updateSchedule_convertBodyToHitl_rejectedForAdmin() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(scheduleStore.readSchedule("r1")).thenReturn(makeCronSchedule("r1"));

        Response response = rest.updateSchedule("r1", hitlSchedule("r1"));

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).updateSchedule(eq("r1"), any());
    }

    @Test
    void readAllSchedules_redactsHitlForEditor() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(scheduleStore.readAllSchedules(anyInt(), anyInt()))
                .thenReturn(List.of(makeCronSchedule("r1"), hitlSchedule("h1")));

        List<ScheduleConfiguration> result = rest.readAllSchedules(null, 500, 0);

        assertEquals(1, result.size());
        assertEquals("r1", result.get(0).getId());
    }

    @Test
    void readAllSchedules_showsHitlForAdmin() throws Exception {
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(scheduleStore.readAllSchedules(anyInt(), anyInt()))
                .thenReturn(List.of(makeCronSchedule("r1"), hitlSchedule("h1")));

        List<ScheduleConfiguration> result = rest.readAllSchedules(null, 500, 0);

        assertEquals(2, result.size());
    }

    // --- Cross-user schedules: a schedule runs AS its userId ---

    /**
     * The schedule surface is the one place a plain {@code eddi-editor} can name an
     * arbitrary {@code userId}. Every fire then acts as that identity, and for a
     * {@code dreamType=dream_consolidation} schedule that means
     * {@code DreamService} prunes, rewrites and permanently deletes the named
     * user's persistent memories — an operation the direct memory API
     * ({@code IRestUserMemoryStore}, roles {@code {eddi-admin, eddi-user}},
     * {@code validateUserAccess} on every method) refuses that caller outright.
     * These tests pin the guard that stops the schedule API becoming a back door
     * around it.
     */
    private static ScheduleConfiguration dreamSchedule(String id, String userId) {
        var s = makeCronSchedule(id);
        s.setName("dream-" + id);
        s.setCronExpression("0 3 * * *");
        s.setUserId(userId);
        s.setMetadata(Map.of("dreamType", "dream_consolidation"));
        return s;
    }

    /** Authenticate the mocked identity as a plain (non-admin) editor. */
    private void asEditor(String principalName) {
        when(identity.hasRole("eddi-admin")).thenReturn(false);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(new TestPrincipal(principalName));
    }

    /** Authenticate the mocked identity as an admin. */
    private void asAdmin(String principalName) {
        when(identity.hasRole("eddi-admin")).thenReturn(true);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(new TestPrincipal(principalName));
    }

    @Test
    void createSchedule_agentTheCallerMayNotUse_refused() throws Exception {
        // A schedule converses with its agent on every fire, and the fire is
        // system-initiated — below the USE gate by design. So the gate lives at
        // create time; without it, scheduling a private agent is a standing bypass
        // of the check on /agents/{id}/start.
        asEditor("editor-1");
        var privateAgentGuard = mock(ResourceAccessGuard.class);
        doThrow(new io.quarkus.security.ForbiddenException("no")).when(privateAgentGuard).requireAgentUseAccess(anyString());
        setField(rest, "resourceAccessGuard", privateAgentGuard);

        assertThrows(io.quarkus.security.ForbiddenException.class, () -> rest.createSchedule(dreamSchedule("d9", "editor-1")));
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    void createSchedule_actingAsAnotherUser_forbiddenForEditor() throws Exception {
        asEditor("editor-1");

        Response response = rest.createSchedule(dreamSchedule("d1", "victim-42"));

        assertEquals(403, response.getStatus());
        verify(scheduleStore, never()).createSchedule(any());
    }

    @Test
    void createSchedule_actingAsSelf_allowedForEditor() throws Exception {
        asEditor("editor-1");
        when(scheduleStore.createSchedule(any())).thenReturn("d2");

        Response response = rest.createSchedule(dreamSchedule("d2", "editor-1"));

        assertEquals(201, response.getStatus());
        assertEquals("editor-1", ((ScheduleConfiguration) response.getEntity()).getUserId());
        verify(scheduleStore).createSchedule(any());
    }

    @Test
    void createSchedule_actingAsAnotherUser_allowedForAdmin() throws Exception {
        // Admins legitimately schedule work on behalf of any user.
        asAdmin("root");
        when(scheduleStore.createSchedule(any())).thenReturn("d3");

        Response response = rest.createSchedule(dreamSchedule("d3", "victim-42"));

        assertEquals(201, response.getStatus());
        assertEquals("victim-42", ((ScheduleConfiguration) response.getEntity()).getUserId());
    }

    @Test
    void createSchedule_systemSchedulerPlaceholder_allowedForEditor() throws Exception {
        // 'system:scheduler' is not a real principal (DreamService refuses to
        // consolidate for it) and it is what applyDefaults/readSchedule hand back, so
        // round-tripping it must not turn into a 403.
        asEditor("editor-1");
        when(scheduleStore.createSchedule(any())).thenReturn("s1");

        var schedule = makeCronSchedule("s1");
        schedule.setUserId("system:scheduler");

        Response response = rest.createSchedule(schedule);

        assertEquals(201, response.getStatus());
        verify(scheduleStore).createSchedule(any());
    }

    @Test
    void updateSchedule_repointingToAnotherUser_forbiddenForEditor() throws Exception {
        asEditor("editor-1");
        // Stored schedule is an innocuous system schedule; the BODY re-points it at a
        // victim — the conversion path the create guard would otherwise miss.
        when(scheduleStore.readSchedule("r1")).thenReturn(makeCronSchedule("r1"));

        Response response = rest.updateSchedule("r1", dreamSchedule("r1", "victim-42"));

        assertEquals(403, response.getStatus());
        verify(scheduleStore, never()).updateSchedule(eq("r1"), any());
    }

    /**
     * The body-only guard was not enough, and this is the hole it left. A body that
     * omits {@code userId} is exempt — it means "run as the system scheduler" — so
     * a non-admin could PUT over a schedule STORED against a victim and pass the
     * check with room to spare, retargeting its agent/cron/message or disarming it.
     * Update has to answer both questions: may I touch THIS schedule (stored
     * owner), and may I make it act as THAT identity (body owner)?
     */
    @Test
    void updateSchedule_ofAnotherUsersStoredSchedule_forbiddenForEditor() throws Exception {
        asEditor("editor-1");
        // Stored schedule belongs to the victim...
        when(scheduleStore.readSchedule("v1")).thenReturn(dreamSchedule("v1", "victim-42"));

        // ...and the body leaves userId unset, which the body-only guard waved through.
        Response response = rest.updateSchedule("v1", makeCronSchedule("v1"));

        assertEquals(403, response.getStatus(), "a non-admin must not overwrite a schedule owned by another user");
        verify(scheduleStore, never()).updateSchedule(eq("v1"), any());
    }

    /**
     * An unverifiable owner must DENY, never allow.
     * <p>
     * Be precise about what this proves. On the update path
     * {@code requireAdminForHitl} reads the same schedule first and already fails
     * closed, so it is that guard producing the 500 here — the ownership guard's
     * own fail-closed branch is defence in depth, not the thing standing between a
     * caller and the mutation today. What the assertion genuinely pins is the
     * property that matters: a store failure never results in the update
     * proceeding.
     */
    @Test
    void updateSchedule_whenOwnershipCannotBeRead_refusesInsteadOfFailingOpen() throws Exception {
        asEditor("editor-1");
        when(scheduleStore.readSchedule("x1")).thenThrow(new IResourceStore.ResourceStoreException("store down"));

        Response response = rest.updateSchedule("x1", makeCronSchedule("x1"));

        assertEquals(500, response.getStatus(), "an unverifiable owner must deny, not allow");
        verify(scheduleStore, never()).updateSchedule(eq("x1"), any());
    }

    @Test
    void updateSchedule_ofMissingSchedule_stillReportsNotFound() throws Exception {
        asEditor("editor-1");
        when(scheduleStore.readSchedule("gone")).thenThrow(new IResourceStore.ResourceNotFoundException("nope"));
        doThrow(new IResourceStore.ResourceNotFoundException("nope")).when(scheduleStore).updateSchedule(eq("gone"), any());

        // A missing schedule must not be reported as forbidden — otherwise the guard
        // becomes an oracle for which schedule ids exist.
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> rest.updateSchedule("gone", makeCronSchedule("gone")));
    }

    /**
     * A blank time zone answered 500 on BOTH create and update. The originally
     * reported mechanism — update skipping {@code applyDefaults} — was not the
     * cause: {@code validateSchedule} runs before defaulting on both paths, and it
     * was its own unguarded {@code ZoneId.of} that threw, because the three call
     * sites disagreed about whether "absent" meant null or also blank. A blank
     * string is non-null. See {@code zoneOf}.
     */
    @Test
    void createSchedule_withBlankTimeZone_appliesTheDefaultInsteadOfThrowing() throws Exception {
        when(scheduleStore.createSchedule(any())).thenReturn("new-id");

        var body = makeCronSchedule("c1");
        body.setTimeZone("");

        Response response = rest.createSchedule(body);

        assertEquals(201, response.getStatus(), "create carried the identical defect");
        assertEquals("UTC", ((ScheduleConfiguration) response.getEntity()).getTimeZone());
    }

    @Test
    void updateSchedule_withBlankTimeZone_appliesTheDefaultInsteadOfThrowing() throws Exception {
        asAdmin("root");
        when(scheduleStore.readSchedule("t1")).thenReturn(makeCronSchedule("t1"));

        var body = makeCronSchedule("t1");
        body.setTimeZone("");

        Response response = rest.updateSchedule("t1", body);

        assertEquals(200, response.getStatus(), "a blank timeZone must default, not 500");
        assertEquals("UTC", body.getTimeZone());
        verify(scheduleStore).updateSchedule(eq("t1"), any());
    }

    /**
     * The other half, and the one that touches ownership: an absent userId was
     * stored as null rather than the scheduler placeholder. Ownership treats null
     * as unowned, so an editor updating their OWN schedule silently made it
     * writable by every other editor.
     */
    @Test
    void updateSchedule_withoutUserId_storesTheSchedulerPlaceholderNotNull() throws Exception {
        asEditor("editor-1");
        when(scheduleStore.readSchedule("u1")).thenReturn(dreamSchedule("u1", "editor-1"));

        var body = makeCronSchedule("u1");
        body.setUserId(null);

        Response response = rest.updateSchedule("u1", body);

        assertEquals(200, response.getStatus());
        assertEquals("system:scheduler", body.getUserId(), "an omitted userId must not persist as null — null reads as unowned");
        verify(scheduleStore).updateSchedule(eq("u1"), any());
    }

    @Test
    void updateSchedule_ofOwnStoredSchedule_allowedForEditor() throws Exception {
        asEditor("editor-1");
        when(scheduleStore.readSchedule("m1")).thenReturn(dreamSchedule("m1", "editor-1"));

        Response response = rest.updateSchedule("m1", dreamSchedule("m1", "editor-1"));

        assertEquals(200, response.getStatus());
        verify(scheduleStore).updateSchedule(eq("m1"), any());
    }

    @Test
    void updateSchedule_ofAnotherUsersStoredSchedule_allowedForAdmin() throws Exception {
        asAdmin("root");
        when(scheduleStore.readSchedule("v1")).thenReturn(dreamSchedule("v1", "victim-42"));

        Response response = rest.updateSchedule("v1", dreamSchedule("v1", "victim-42"));

        assertEquals(200, response.getStatus());
        verify(scheduleStore).updateSchedule(eq("v1"), any());
    }

    @Test
    void fireNow_scheduleActingAsAnotherUser_forbiddenForEditor() throws Exception {
        asEditor("editor-1");
        when(scheduleStore.readSchedule("d1")).thenReturn(dreamSchedule("d1", "victim-42"));

        Response response = rest.fireNow("d1");

        assertEquals(403, response.getStatus());
        // The destructive dispatch must never be reached.
        verify(fireExecutor, never()).fire(any(), any(), anyInt());
    }

    @Test
    void fireNow_scheduleActingAsAnotherUser_allowedForAdmin() throws Exception {
        asAdmin("root");
        when(scheduleStore.readSchedule("d1")).thenReturn(dreamSchedule("d1", "victim-42"));
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(new ScheduleFireLog(
                        "log-d1", "d1", "fire-1", null, Instant.now(), Instant.now(),
                        FireStatus.COMPLETED.name(), "n1", null, null, 1, 0.0));

        Response response = rest.fireNow("d1");

        assertEquals(200, response.getStatus());
        verify(fireExecutor).fire(any(), any(), anyInt());
    }

    @Test
    void fireNow_scheduleActingAsSelf_allowedForEditor() throws Exception {
        asEditor("editor-1");
        when(scheduleStore.readSchedule("d2")).thenReturn(dreamSchedule("d2", "editor-1"));
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(new ScheduleFireLog(
                        "log-d2", "d2", "fire-1", null, Instant.now(), Instant.now(),
                        FireStatus.COMPLETED.name(), "n1", null, null, 1, 0.0));

        Response response = rest.fireNow("d2");

        assertEquals(200, response.getStatus());
        verify(fireExecutor).fire(any(), any(), anyInt());
    }

    @Test
    void fireNow_systemSchedulerSchedule_stillFiresForEditor() throws Exception {
        asEditor("editor-1");
        var schedule = makeCronSchedule("r2");
        schedule.setUserId("system:scheduler");
        when(scheduleStore.readSchedule("r2")).thenReturn(schedule);
        when(fireExecutor.fire(any(), any(), anyInt()))
                .thenReturn(new ScheduleFireLog(
                        "log-r2", "r2", "fire-1", null, Instant.now(), Instant.now(),
                        FireStatus.COMPLETED.name(), "n1", "conv-1", null, 1, 0.0));

        Response response = rest.fireNow("r2");

        assertEquals(200, response.getStatus());
        verify(fireExecutor).fire(any(), any(), anyInt());
    }

    // --- Manual fire: claim, then release through the state machine ---

    /**
     * A manual fire used to run with no claim at all, so it could execute
     * concurrently with the poller's own fire of the same schedule — and with
     * conversationStrategy=persistent both pushed a turn into the SAME
     * conversation.
     */
    @Test
    void fireNow_claimsBeforeFiringAndReleasesAfterwards() throws Exception {
        var schedule = makeCronSchedule("f1");
        when(scheduleStore.readSchedule("f1")).thenReturn(schedule);
        var fireLog = fireLog("f1", FireStatus.COMPLETED.name());
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(fireLog);

        Response response = rest.fireNow("f1");

        assertEquals(200, response.getStatus());
        var inOrder = inOrder(pollerService, fireExecutor);
        inOrder.verify(pollerService).claimForManualFire(schedule);
        inOrder.verify(fireExecutor).fire(eq(schedule), any(), anyInt());
        inOrder.verify(pollerService).recordManualFireOutcome(schedule, fireLog);
    }

    @Test
    void fireNow_refusedClaimIsAConflictNotAServerError() throws Exception {
        when(scheduleStore.readSchedule("f2")).thenReturn(makeCronSchedule("f2"));
        when(pollerService.claimForManualFire(any())).thenReturn(false);

        Response response = rest.fireNow("f2");

        assertEquals(409, response.getStatus());
        verify(fireExecutor, never()).fire(any(), any(), anyInt());
    }

    /** A manual fire that throws must still release the claim. */
    @Test
    void fireNow_fireThrows_stillReleasesTheClaim() throws Exception {
        var schedule = makeCronSchedule("f3");
        when(scheduleStore.readSchedule("f3")).thenReturn(schedule);
        when(fireExecutor.fire(any(), any(), anyInt())).thenThrow(new RuntimeException("boom"));

        assertThrows(jakarta.ws.rs.InternalServerErrorException.class, () -> rest.fireNow("f3"));

        verify(pollerService).recordManualFireOutcome(schedule, null);
    }

    /**
     * A refused claim is not always "someone else is firing it". {@code tryClaim}
     * accepts only PENDING, FAILED-and-due and a CLAIMED row with an expired lease,
     * so a dead-lettered schedule is refused too — and it is one of the states an
     * operator presses "Fire now" from. Answering it with "already being fired" is
     * factually wrong and hides {@code /retry}, the endpoint that would actually
     * help.
     */
    @Test
    void fireNow_deadLetteredScheduleSaysSoInsteadOfClaimingSomeoneIsFiringIt() throws Exception {
        var schedule = makeCronSchedule("f4");
        schedule.setFireStatus(FireStatus.DEAD_LETTERED);
        schedule.setFailCount(5);
        when(scheduleStore.readSchedule("f4")).thenReturn(schedule);
        when(pollerService.claimForManualFire(any())).thenReturn(false);

        Response response = rest.fireNow("f4");

        assertEquals(409, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("dead-lettered"), "expected the dead-letter reason, got: " + body);
        assertTrue(body.contains("/retry"), "expected the retry endpoint to be named, got: " + body);
        assertFalse(body.contains("already being fired"),
                "a dead-lettered schedule is not being fired by anyone: " + body);
    }

    /**
     * Same defect, the other refused state: a FAILED schedule whose retry backoff
     * has not elapsed. {@code retryDeadLetter} only covers DEAD_LETTERED, so this
     * one has no direct recovery path at all and the message must at least say what
     * is actually happening.
     */
    @Test
    void fireNow_failedScheduleInBackoffSaysSoInsteadOfClaimingSomeoneIsFiringIt() throws Exception {
        var schedule = makeCronSchedule("f5");
        schedule.setFireStatus(FireStatus.FAILED);
        schedule.setFailCount(2);
        schedule.setNextRetryAt(Instant.parse("2099-01-01T00:00:00Z"));
        when(scheduleStore.readSchedule("f5")).thenReturn(schedule);
        when(pollerService.claimForManualFire(any())).thenReturn(false);

        Response response = rest.fireNow("f5");

        assertEquals(409, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("FAILED"), "expected the FAILED state to be named, got: " + body);
        assertTrue(body.contains("2099-01-01T00:00:00Z"),
                "expected the pending retry time, got: " + body);
        assertFalse(body.contains("already being fired"),
                "a schedule waiting out its backoff is not being fired by anyone: " + body);
    }

    /** A genuinely CLAIMED schedule keeps the original wording. */
    @Test
    void fireNow_claimedScheduleStillSaysItIsAlreadyBeingFired() throws Exception {
        var schedule = makeCronSchedule("f6");
        schedule.setFireStatus(FireStatus.CLAIMED);
        when(scheduleStore.readSchedule("f6")).thenReturn(schedule);
        when(pollerService.claimForManualFire(any())).thenReturn(false);

        Response response = rest.fireNow("f6");

        assertEquals(409, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("already being fired"));
    }

    /**
     * The attempt number of a manual fire is the attempt it actually is, not a
     * constant 1.
     * <p>
     * {@code fireNow} passed a literal 1, so a schedule already on its third failed
     * attempt logged a manual retry as "attempt 1" — the fire log then read as a
     * fresh first try and gave no hint that this schedule had been failing. Every
     * other assertion in this class uses {@code anyInt()}, and the one place a
     * concrete number was stubbed used a schedule with failCount 0, where the old
     * constant and the new expression are indistinguishable. This uses a non-zero
     * failCount, which is the whole point of the change.
     */
    @Test
    void fireNow_attemptNumberContinuesTheFailureSequence() throws Exception {
        var schedule = makeCronSchedule("f7");
        schedule.setFailCount(3);
        when(scheduleStore.readSchedule("f7")).thenReturn(schedule);
        when(fireExecutor.fire(any(), any(), anyInt())).thenReturn(fireLog("f7", FireStatus.COMPLETED.name()));

        assertEquals(200, rest.fireNow("f7").getStatus());

        verify(fireExecutor).fire(eq(schedule), any(), eq(4));
        verify(fireExecutor, never()).fire(any(), any(), eq(1));
    }

    /**
     * The claim release must not run under a set interrupt flag.
     * <p>
     * {@code ScheduleFireExecutor.fire} deliberately re-asserts an interrupt that a
     * blocking call inside it consumed, so on the interrupted path — client
     * disconnect, shutdown, RESTEasy cancellation — {@code fireNow}'s finally block
     * runs with the flag set. The synchronous Mongo driver then throws
     * {@code MongoInterruptedException} on connection checkout, and
     * {@code recordManualFireOutcome} swallows it: the claim is never released and
     * {@code failCount} never increments, which is exactly the leak the finally
     * exists to prevent, self-healing only once the lease expires.
     * {@code SchedulePollerService.fireClaimedSchedule} parks the flag around its
     * identical bookkeeping; this path has to do the same — and restore it
     * afterwards, or the cancellation signal is lost instead.
     */
    @Test
    void fireNow_interruptedFire_releasesTheClaimWithTheFlagParkedThenRestoresIt() throws Exception {
        var schedule = makeCronSchedule("f8");
        when(scheduleStore.readSchedule("f8")).thenReturn(schedule);
        // Mirror ScheduleFireExecutor.restoreInterrupt: the flag is set when fire()
        // returns on the interrupted path.
        when(fireExecutor.fire(any(), any(), anyInt())).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            return fireLog("f8", FireStatus.FAILED.name());
        });
        var flagDuringRelease = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(inv -> {
            flagDuringRelease.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(pollerService).recordManualFireOutcome(any(), any());

        try {
            rest.fireNow("f8");

            assertFalse(flagDuringRelease.get(),
                    "the bookkeeping write must not run under a set interrupt flag — the sync Mongo "
                            + "driver throws MongoInterruptedException on connection checkout and the claim leaks");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "the interrupt must be re-asserted afterwards, or the cancellation signal is swallowed");
        } finally {
            Thread.interrupted(); // never leak a set flag into the next test
        }
    }

    // --- Update: a store failure must not silently un-claim a running fire ---

    /**
     * {@code carryOverNonEditableFields} used to swallow EVERY exception from
     * {@code readSchedule} and return, after which the PUT went ahead with the
     * body's values — and {@code applyDefaults} has already stamped
     * {@code fireStatus = PENDING} onto the body. That is precisely the mid-fire
     * un-claim the method exists to prevent, reintroduced by nothing worse than a
     * transient store error. Only NOT-FOUND may be shrugged off (the store's own
     * update is about to surface the 404); anything else must abort the update.
     */
    @Test
    void update_storeFailureWhilePreservingNonEditableFieldsAbortsInsteadOfUnClaiming() throws Exception {
        var stored = makeCronSchedule("u9");
        stored.setUserId("system:scheduler");
        when(scheduleStore.readSchedule("u9"))
                .thenReturn(stored)
                .thenReturn(stored)
                .thenThrow(new IResourceStore.ResourceStoreException("db down"));

        var body = makeCronSchedule("u9");
        body.setFireStatus(null);
        body.setUserId("system:scheduler");

        assertThrows(jakarta.ws.rs.InternalServerErrorException.class, () -> rest.updateSchedule("u9", body));

        verify(scheduleStore, never()).updateSchedule(anyString(), any());
    }

    // --- Validation: client mistakes are 400, not 500 ---

    /**
     * {@code Instant.parse} throws {@link java.time.format.DateTimeParseException},
     * which is a {@code DateTimeException} and NOT an
     * {@code IllegalArgumentException} — so it slipped past the 400 handler and
     * surfaced as a 500 "Failed to create schedule", with a stack trace, for a
     * typo.
     */
    @Test
    void create_malformedOneTimeAt_isBadRequestNotServerError() throws Exception {
        var s = makeCronSchedule("bad-1");
        s.setCronExpression(null);
        s.setOneTimeAt("2026-09-03 10:00"); // not ISO-8601

        Response response = rest.createSchedule(s);

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).createSchedule(any());
    }

    /** An empty POST body deserializes to null; dereferencing it produced a 500. */
    @Test
    void create_nullBody_isBadRequestNotServerError() {
        Response response = rest.createSchedule(null);

        assertEquals(400, response.getStatus());
    }

    /**
     * A syntactically valid cron that can never match (February 30th) made
     * CronParser throw {@code IllegalStateException}, which is not an
     * {@code IllegalArgumentException} and so escaped as a 500.
     */
    @Test
    void create_unsatisfiableCron_isBadRequestNotServerError() throws Exception {
        var s = makeCronSchedule("bad-2");
        s.setCronExpression("0 0 30 2 *");

        Response response = rest.createSchedule(s);

        assertEquals(400, response.getStatus());
        verify(scheduleStore, never()).createSchedule(any());
    }

    // --- createdBy ---

    /**
     * createdBy exists to answer "who created this schedule" when a rogue one keeps
     * starting conversations. Nothing on this path ever set it, so every schedule
     * created through the public API had it null.
     */
    @Test
    void create_stampsCreatedByFromTheAuthenticatedCaller() throws Exception {
        asEditor("alice");
        when(scheduleStore.createSchedule(any())).thenReturn("new-id");

        rest.createSchedule(makeCronSchedule(null));

        var captor = org.mockito.ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).createSchedule(captor.capture());
        assertEquals("alice", captor.getValue().getCreatedBy());
    }

    // --- Update preserves provenance and the fire lifecycle ---

    /**
     * A PUT edits configuration; it must not rewrite provenance or the fire
     * lifecycle. Both are absent from the normal request shape, so taking them from
     * the body nulled createdAt/createdBy/lastFired and reset the state of a fire
     * that was still running — re-opening its claim.
     */
    @Test
    void update_carriesOverProvenanceAndClaimStateFromTheStoredSchedule() throws Exception {
        var stored = makeCronSchedule("u1");
        Instant createdAt = Instant.now().minusSeconds(86400);
        stored.setCreatedAt(createdAt);
        stored.setCreatedBy("alice");
        stored.setLastFired(Instant.now().minusSeconds(600));
        stored.setFireStatus(FireStatus.CLAIMED);
        stored.setClaimedBy("node-7");
        stored.setFailCount(2);
        stored.setPersistentConversationId("conv-keep");
        when(scheduleStore.readSchedule("u1")).thenReturn(stored);

        var body = makeCronSchedule("u1"); // the normal PUT shape: no audit fields
        body.setName("renamed");
        rest.updateSchedule("u1", body);

        var captor = org.mockito.ArgumentCaptor.forClass(ScheduleConfiguration.class);
        verify(scheduleStore).updateSchedule(eq("u1"), captor.capture());
        ScheduleConfiguration persisted = captor.getValue();
        assertEquals("renamed", persisted.getName(), "the editable fields must still be applied");
        assertEquals(createdAt, persisted.getCreatedAt());
        assertEquals("alice", persisted.getCreatedBy());
        assertNotNull(persisted.getLastFired());
        assertEquals(FireStatus.CLAIMED, persisted.getFireStatus(), "an edit must not un-claim a running fire");
        assertEquals("node-7", persisted.getClaimedBy());
        assertEquals(2, persisted.getFailCount());
        assertEquals("conv-keep", persisted.getPersistentConversationId());
    }

    // --- Enable re-arms a one-shot ---

    /**
     * computeNextFireForSchedule handled only HEARTBEAT and cron and fell through
     * to null for a one-shot. Both stores skip the re-arm when nextFire is null, so
     * enable answered 200, the row read back enabled=true, and nextFire stayed NULL
     * — which findDueSchedules can never match on either backend. The schedule sat
     * enabled-but-dead forever, with no error anywhere.
     */
    @Test
    void enable_oneShotSchedule_isArmedWithANonNullNextFire() throws Exception {
        var oneShot = makeCronSchedule("os1");
        oneShot.setCronExpression(null);
        oneShot.setOneTimeAt(Instant.now().minusSeconds(3600).toString()); // already fired
        oneShot.setEnabled(false);
        when(scheduleStore.readSchedule("os1")).thenReturn(oneShot);

        Response response = rest.enableSchedule("os1");

        assertEquals(200, response.getStatus());
        var nextFire = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(scheduleStore).setScheduleEnabled(eq("os1"), eq(true), nextFire.capture());
        assertNotNull(nextFire.getValue(), "a re-enabled one-shot with a null nextFire can never be claimed again");
    }

    // --- Fire-log limits ---

    /**
     * The limit was passed straight through, and the backends read it differently:
     * the MongoDB driver treats limit(0) as "no limit" while PostgreSQL's LIMIT 0
     * returns nothing, so ?limit=0 dumped every fire log ever written on one
     * backend and an empty list on the other.
     */
    @Test
    void readFireLogs_nonPositiveLimit_isRejected() {
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> rest.readFireLogs("s1", 0));
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> rest.readFireLogs("s1", -5));
    }

    @Test
    void readFireLogs_hugeLimit_isCapped() throws Exception {
        when(scheduleStore.readFireLogs(eq("s1"), anyInt())).thenReturn(List.of());

        rest.readFireLogs("s1", 10_000_000);

        verify(scheduleStore).readFireLogs("s1", 500);
    }

    @Test
    void readFailedFires_nonPositiveLimit_isRejected() {
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> rest.readFailedFires(0));
    }

    @Test
    void readAllSchedules_nonPositiveLimit_isRejected() {
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> rest.readAllSchedules(null, 0, 0));
    }

    @Test
    void readAllSchedules_hugeLimit_isCappedAndNegativeOffsetClamped() throws Exception {
        when(scheduleStore.readAllSchedules(anyInt(), anyInt())).thenReturn(List.of());

        rest.readAllSchedules(null, 999_999, -10);

        verify(scheduleStore).readAllSchedules(1000, 0);
    }

    // --- Heartbeat descriptions ---

    /**
     * Truncating integer division described 90 s as "Every minute", 5400 s as
     * "Every hour" and 129600 s as "Every day" — telling an operator reading the
     * list that the agent fires 1.5x more often than it does. A coarser unit is
     * only correct when the interval divides into it exactly.
     */
    @Test
    void readSchedule_heartbeatDescription_doesNotRoundTheIntervalDown() throws Exception {
        assertEquals("Every 90 seconds", describeHeartbeatVia(90L));
        assertEquals("Every 90 minutes", describeHeartbeatVia(5400L));
        assertEquals("Every 36 hours", describeHeartbeatVia(129600L));
        // exact multiples still read naturally
        assertEquals("Every minute", describeHeartbeatVia(60L));
        assertEquals("Every hour", describeHeartbeatVia(3600L));
        assertEquals("Every day", describeHeartbeatVia(86400L));
        assertEquals("Every 30 seconds", describeHeartbeatVia(30L));
    }

    private String describeHeartbeatVia(long intervalSeconds) throws Exception {
        var s = new ScheduleConfiguration();
        s.setId("hb");
        s.setTriggerType(TriggerType.HEARTBEAT);
        s.setAgentId("agent-1");
        s.setHeartbeatIntervalSeconds(intervalSeconds);
        when(scheduleStore.readSchedule("hb")).thenReturn(s);
        return rest.readSchedule("hb").getCronDescription();
    }

    private static ScheduleFireLog fireLog(String scheduleId, String status) {
        return new ScheduleFireLog("log-1", scheduleId, "fire-1", Instant.now(), Instant.now(), Instant.now(), status,
                "test-instance", "conv-1", null, 1, 0.0);
    }

    private record TestPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    private static ScheduleConfiguration makeCronSchedule(String id) {
        var s = new ScheduleConfiguration();
        s.setId(id);
        s.setName("Test");
        s.setTriggerType(TriggerType.CRON);
        s.setAgentId("agent-1");
        s.setCronExpression("0 9 * * *");
        s.setMessage("Hello");
        s.setTimeZone("UTC");
        s.setFireStatus(FireStatus.PENDING);
        return s;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException e) {
            // Try superclass
            try {
                var field = target.getClass().getSuperclass().getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception ex) {
                throw new RuntimeException("Cannot set field " + fieldName, ex);
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot set field " + fieldName, e);
        }
    }

    /**
     * A guard that admits every agent — a bare mock's void
     * {@code requireAgentUseAccess} does nothing. These tests exercise schedule
     * semantics; the USE gate has its own.
     */
    private static ResourceAccessGuard permissiveResourceGuard() {
        return mock(ResourceAccessGuard.class);
    }
}
