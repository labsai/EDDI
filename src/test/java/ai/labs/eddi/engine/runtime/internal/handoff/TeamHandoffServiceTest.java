/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.model.SharedTaskList.Bet;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptPolicy;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.GroupHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.HandoffOrigin;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.SourceKind;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Trigger;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult.Outcome;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaKey;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaStoreException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamHandoffServiceTest {

    static final String TARGET = "target-group";
    static final String SOURCE_GROUP = "source-group";

    HandoffFakes.WorkspaceStore workspaces;
    HandoffFakes.QuotaStore quotas;
    IAgentGroupStore groupStore;
    AuditLedgerService audit;
    MeterRegistry meters;
    Instance<HandoffAdmission> admissions;
    MutableClock clock;
    TeamHandoffService service;

    /** The accept policy the target currently publishes. */
    AcceptPolicy accept;

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-02T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        workspaces = new HandoffFakes.WorkspaceStore();
        quotas = new HandoffFakes.QuotaStore();
        groupStore = mock(IAgentGroupStore.class);
        audit = mock(AuditLedgerService.class);
        when(audit.isEnabled()).thenReturn(true);
        meters = new SimpleMeterRegistry();
        admissions = mock(Instance.class);
        when(admissions.isUnsatisfied()).thenReturn(true);
        clock = new MutableClock();
        accept = new AcceptPolicy(
                List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, null), new AcceptRule("agent:agent-1", null, null)), 0,
                0);
        var resourceId = new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return TARGET;
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        };
        when(groupStore.getCurrentResourceId(TARGET)).thenReturn(resourceId);
        when(groupStore.read(any(), any())).thenAnswer(inv -> targetConfig());
        service = new TeamHandoffService(workspaces, groupStore, quotas, audit, meters, admissions, 3, Duration.ofDays(30), clock);
    }

    AgentGroupConfiguration targetConfig() {
        var config = new AgentGroupConfiguration();
        config.setHandoffConfig(new GroupHandoffConfig(null, null, 0, 0, 0, null, accept));
        return config;
    }

    HandoffRequest groupRequest(String subject) {
        return new HandoffRequest(SourceKind.GROUP, SOURCE_GROUP, null, "gc-1", "member-1", "user-1", TARGET, subject, "details", 6, Trigger.TOOL,
                null,
                null, null, 0, List.of(), null, 0, null);
    }

    HandoffRequest agentRequest(String subject) {
        return new HandoffRequest(SourceKind.AGENT, "agent-1", 3, "conv-1", null, "user-1", TARGET, subject, "details", null, Trigger.TOOL, null,
                null,
                null, 0, List.of(), null, 0, null);
    }

    HandoffRequest withLineage(HandoffRequest r, int hop, List<String> lineage) {
        return new HandoffRequest(r.sourceKind(), r.sourceId(), r.sourceVersion(), r.sourceConversationId(), r.sourceMemberAgentId(), r.userId(),
                r.targetGroupId(), r.subject(), r.description(), r.priority(), r.trigger(), r.decisionRef(), r.artifactId(), r.reference(), hop,
                lineage,
                r.dedupeKey(), r.maxDescriptionChars(), r.runQuota());
    }

    HandoffRequest withDedupe(HandoffRequest r, String key) {
        return new HandoffRequest(r.sourceKind(), r.sourceId(), r.sourceVersion(), r.sourceConversationId(), r.sourceMemberAgentId(), r.userId(),
                r.targetGroupId(), r.subject(), r.description(), r.priority(), r.trigger(), r.decisionRef(), r.artifactId(), r.reference(), r.hop(),
                r.lineage(), key, r.maxDescriptionChars(), r.runQuota());
    }

    HandoffRequest withRunQuota(HandoffRequest r, HandoffRequest.RunQuota quota) {
        return new HandoffRequest(r.sourceKind(), r.sourceId(), r.sourceVersion(), r.sourceConversationId(), r.sourceMemberAgentId(), r.userId(),
                r.targetGroupId(), r.subject(), r.description(), r.priority(), r.trigger(), r.decisionRef(), r.artifactId(), r.reference(), r.hop(),
                r.lineage(), r.dedupeKey(), r.maxDescriptionChars(), quota);
    }

    GroupWorkspace workspace() throws Exception {
        return workspaces.readOrCreate(TARGET);
    }

    // ------------------------------------------------------------------ consent

    @Nested
    class Consent {

        @Test
        void aSourceTheTargetDoesNotListIsRefusedAndNothingIsWritten() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:somebody-else", IntakeMode.AUTO, null)), 0, 0);
            var result = service.file(groupRequest("X"));
            assertEquals(Outcome.NOT_ACCEPTED, result.outcome());
            assertEquals(0, workspace().getBacklog().size());
            assertEquals(0, workspace().getHandoffs().inboxSize());
            assertEquals(0, quotas.total(), "a refused source must not even consume a day-cap slot");
        }

        @Test
        void aTargetWithNoHandoffConfigAcceptsNothing() {
            accept = null;
            assertEquals(Outcome.NOT_ACCEPTED, service.file(groupRequest("X")).outcome());
        }

        @Test
        void consentIsPerKindAnAgentIdIsNotAGroupId() {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:agent-1", IntakeMode.AUTO, null)), 0, 0);
            assertEquals(Outcome.NOT_ACCEPTED, service.file(agentRequest("X")).outcome());
        }

        @Test
        void aMissingTargetIsNotAccepted() throws Exception {
            when(groupStore.getCurrentResourceId(TARGET)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
            assertEquals(Outcome.NOT_ACCEPTED, service.file(groupRequest("X")).outcome());
        }

        @Test
        void aConfigStoreFailureFailsClosed() throws Exception {
            when(groupStore.read(any(), any())).thenThrow(new IResourceStore.ResourceStoreException("db down"));
            var result = service.file(groupRequest("X"));
            assertEquals(Outcome.UNAVAILABLE, result.outcome());
            assertEquals(0, workspace().getBacklog().size());
        }

        @Test
        void aNewlyRevokedAcceptRuleTakesEffectOnTheNextCall() throws Exception {
            assertTrue(service.file(groupRequest("first")).success());
            accept = null;
            assertEquals(Outcome.NOT_ACCEPTED, service.file(groupRequest("second")).outcome());
            assertEquals(1, workspace().getBacklog().size());
        }
    }

    // ------------------------------------------------------------------ lineage

    @Nested
    class Lineage {

        @Test
        void selfHandoffIsRefused() {
            var self = new HandoffRequest(SourceKind.GROUP, TARGET, null, "gc", null, "u", TARGET, "X", "d", null, Trigger.TOOL, null, null, null, 0,
                    List.of(), null, 0, null);
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + TARGET, IntakeMode.AUTO, null)), 0, 0);
            assertEquals(Outcome.LOOP, service.file(self).outcome());
        }

        @Test
        void aTargetAlreadyInTheLineageIsRefused() throws Exception {
            var r = withLineage(groupRequest("X"), 2, List.of("group:" + TARGET, "agent:other"));
            var result = service.file(r);
            assertEquals(Outcome.LOOP, result.outcome());
            assertEquals(0, workspace().getBacklog().size());
        }

        @Test
        void aTargetNotInTheLineageIsFine() {
            var r = withLineage(groupRequest("X"), 2, List.of("group:elsewhere", "agent:other"));
            assertEquals(Outcome.FILED, service.file(r).outcome());
        }

        @Test
        void theHopLimitIsExactlyMaxHops() {
            // maxHops = 3: work that has crossed 2 boundaries may cross a third; 3 may not.
            assertEquals(Outcome.FILED, service.file(withLineage(groupRequest("ok"), 2, List.of())).outcome());
            assertEquals(Outcome.LOOP, service.file(withLineage(groupRequest("too far"), 3, List.of())).outcome());
        }

        @Test
        void theOriginRecordsTheNextHopAndTheChainIncludingTheSource() throws Exception {
            service.file(withLineage(groupRequest("X"), 1, List.of("agent:first")));
            HandoffOrigin origin = workspace().getHandoffs().getOrigins().values().iterator().next();
            assertEquals(2, origin.hop());
            assertEquals(List.of("agent:first", "group:" + SOURCE_GROUP), origin.lineage());
        }

        @Test
        void aLongChainKeepsTheNewestLinks() throws Exception {
            var chain = new ArrayList<String>();
            for (int i = 0; i < 20; i++) {
                chain.add("agent:a" + i);
            }
            var big = new TeamHandoffService(workspaces, groupStore, quotas, audit, meters, admissions, 99, Duration.ofDays(30), clock);
            big.file(withLineage(groupRequest("X"), 20, chain));
            HandoffOrigin origin = workspace().getHandoffs().getOrigins().values().iterator().next();
            assertEquals(WorkspaceHandoffs.MAX_LINEAGE, origin.lineage().size());
            assertEquals("group:" + SOURCE_GROUP, origin.lineage().get(origin.lineage().size() - 1));
            assertEquals("agent:a13", origin.lineage().get(0));
        }
    }

    // --------------------------------------------------------------------- text

    @Nested
    class Text {

        @Test
        void aBlankSubjectIsInvalid() {
            assertEquals(Outcome.INVALID, service.file(groupRequest("   ")).outcome());
            assertEquals(Outcome.INVALID, service.file(groupRequest(null)).outcome());
        }

        @Test
        void lengthCapsApply() {
            assertEquals(Outcome.INVALID, service.file(groupRequest("s".repeat(201))).outcome());
            var longDesc = new HandoffRequest(SourceKind.GROUP, SOURCE_GROUP, null, "gc", null, "u", TARGET, "ok", "d".repeat(101), null,
                    Trigger.TOOL,
                    null, null, null, 0, List.of(), null, 100, null);
            assertEquals(Outcome.INVALID, service.file(longDesc).outcome());
        }

        @Test
        void theHardDescriptionCeilingHoldsEvenForAGenerousPolicy() {
            var r = new HandoffRequest(SourceKind.GROUP, SOURCE_GROUP, null, "gc", null, "u", TARGET, "ok", "d".repeat(4001), null, Trigger.TOOL,
                    null,
                    null, null, 0, List.of(), null, 999_999, null);
            assertEquals(Outcome.INVALID, service.file(r).outcome());
        }

        @Test
        void controlAndFormatCharactersAreStripped() throws Exception {
            String bidi = new String(Character.toChars(0x202E));
            String zeroWidth = new String(Character.toChars(0x200B));
            var r = new HandoffRequest(SourceKind.GROUP, SOURCE_GROUP, null, "gc", null, "u", TARGET,
                    "Hel\nlo" + bidi + " wor" + zeroWidth + "ld\u0007",
                    "line1\nline2\u0000" + bidi, null, Trigger.TOOL, null, null, null, 0, List.of(), null, 0, null);
            assertEquals(Outcome.FILED, service.file(r).outcome());
            TaskItem task = workspace().getBacklog().getTasks().get(0);
            assertEquals("Hel lo world", task.subject());
            assertEquals("line1\nline2", task.description());
        }
    }

    // ---------------------------------------------------------------- run cap

    @Nested
    class RunCap {

        static final class Cap implements HandoffRequest.RunQuota {
            private final int cap;
            private int taken;
            int releases;

            Cap(int cap) {
                this.cap = cap;
            }

            @Override
            public synchronized boolean tryReserve() {
                if (taken >= cap) {
                    return false;
                }
                taken++;
                return true;
            }

            @Override
            public synchronized void release() {
                taken--;
                releases++;
            }

            synchronized int taken() {
                return taken;
            }
        }

        @Test
        void theCapIsEnforcedUnderContention() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.REVIEW, null)), 500, 100);
            var cap = new Cap(5);
            int threads = 12;
            var pool = Executors.newFixedThreadPool(threads);
            var start = new CountDownLatch(1);
            var filed = new AtomicInteger();
            var capped = new AtomicInteger();
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    int n = i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        // Distinct subjects, so dedupe does not mask the cap.
                        var result = service.file(withRunQuota(groupRequest("subject " + n), cap));
                        if (result.outcome() == Outcome.QUEUED_FOR_REVIEW) {
                            filed.incrementAndGet();
                        } else if (result.outcome() == Outcome.RUN_CAPPED) {
                            capped.incrementAndGet();
                        } else if (result.outcome() != Outcome.CONFLICT) {
                            fail("unexpected " + result);
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : futures) {
                    f.get();
                }
            } finally {
                pool.shutdownNow();
            }
            assertTrue(filed.get() <= 5, "never more than the cap: " + filed.get());
            assertEquals(filed.get(), workspace().getHandoffs().inboxSize(), "every success is on the target, nothing is lost");
            assertEquals(filed.get(), cap.taken(), "every attempt that did not file gave its slot back");
        }

        @Test
        void aRefusalGivesTheSlotBack() {
            var cap = new Cap(1);
            accept = null;
            assertEquals(Outcome.NOT_ACCEPTED, service.file(withRunQuota(groupRequest("a"), cap)).outcome());
            assertEquals(0, cap.taken(), "refused before reservation");
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, null)), 0, 0);
            service.file(withRunQuota(groupRequest("dup"), cap));
            assertEquals(1, cap.taken());
            var again = service.file(withRunQuota(withDedupe(groupRequest("other subject"), "x"), cap));
            assertEquals(Outcome.RUN_CAPPED, again.outcome());
            assertEquals(1, cap.taken());
        }

        @Test
        void aDuplicateDoesNotConsumeARunSlot() {
            var cap = new Cap(2);
            assertEquals(Outcome.FILED, service.file(withRunQuota(groupRequest("same"), cap)).outcome());
            assertEquals(Outcome.DUPLICATE, service.file(withRunQuota(groupRequest("same"), cap)).outcome());
            assertEquals(1, cap.taken());
        }
    }

    // --------------------------------------------------------------- day caps

    @Nested
    class DayCaps {

        @Test
        void thePerEdgeCapRefusesWithTheResetTime() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, 2)), 0, 0);
            assertTrue(service.file(groupRequest("a")).success());
            assertTrue(service.file(groupRequest("b")).success());
            var third = service.file(groupRequest("c"));
            assertEquals(Outcome.DAY_CAPPED, third.outcome());
            assertTrue(third.message().contains("2026-11-03T00:00:00Z"), third.message());
            assertEquals(2, workspace().getBacklog().size());
        }

        @Test
        void theTeamWideIntakeCapCoversAllSources() {
            accept = new AcceptPolicy(
                    List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, null), new AcceptRule("agent:agent-1", IntakeMode.AUTO, null)),
                    2, 0);
            assertTrue(service.file(groupRequest("a")).success());
            assertTrue(service.file(agentRequest("b")).success());
            assertEquals(Outcome.DAY_CAPPED, service.file(agentRequest("c")).outcome());
        }

        @Test
        void aNewUtcDayStartsAFreshWindow() {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, 1)), 0, 0);
            assertTrue(service.file(groupRequest("a")).success());
            assertEquals(Outcome.DAY_CAPPED, service.file(groupRequest("b")).outcome());
            clock.now = clock.now.plus(Duration.ofDays(1));
            assertTrue(service.file(groupRequest("b")).success());
        }

        @Test
        void aRefusedEdgeCapGivesBackNothingItNeverTook() {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, 1)), 0, 0);
            service.file(groupRequest("a"));
            int before = quotas.total();
            service.file(groupRequest("b"));
            assertEquals(before, quotas.total());
        }

        @Test
        void aSlotIsReleasedWhenALaterStepRefuses() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.REVIEW, 10)), 10, 1);
            assertEquals(Outcome.QUEUED_FOR_REVIEW, service.file(groupRequest("a")).outcome());
            int afterFirst = quotas.total();
            assertEquals(2, afterFirst, "edge + intake");
            // The inbox holds 1: this one is refused AFTER both day caps were taken.
            assertEquals(Outcome.INBOX_FULL, service.file(groupRequest("b")).outcome());
            assertEquals(afterFirst, quotas.total(), "the refused attempt must give both slots back");
        }

        @Test
        void aDuplicateGivesItsSlotsBack() {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, 10)), 10, 0);
            service.file(groupRequest("same"));
            int after = quotas.total();
            assertEquals(Outcome.DUPLICATE, service.file(groupRequest("same")).outcome());
            assertEquals(after, quotas.total());
        }

        @Test
        void aQuotaStoreFailureRefusesAndLeavesNothingHeld() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, 10)), 10, 0);
            quotas.fail = true;
            assertEquals(Outcome.UNAVAILABLE, service.file(groupRequest("a")).outcome());
            quotas.fail = false;
            assertEquals(0, quotas.total());
            assertEquals(0, workspace().getBacklog().size());
        }

        @Test
        void aFailureOnTheSecondCounterReleasesTheFirst() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, 10)), 10, 0);
            var flaky = new HandoffFakes.QuotaStore() {
                int calls;

                @Override
                public synchronized Acquisition tryAcquire(ToolQuotaKey key, int limit, Instant expiresAt) {
                    if (++calls == 2) {
                        throw new ToolQuotaStoreException("second counter down", null);
                    }
                    return super.tryAcquire(key, limit, expiresAt);
                }
            };
            var svc = new TeamHandoffService(workspaces, groupStore, flaky, audit, meters, admissions, 3, Duration.ofDays(30), clock);
            assertEquals(Outcome.UNAVAILABLE, svc.file(groupRequest("a")).outcome());
            assertEquals(0, flaky.total(), "the edge slot taken before the failure must be given back");
        }
    }

    // ------------------------------------------------------------------ dedupe

    @Nested
    class Dedupe {

        @Test
        void aSecondIdenticalHandoffIsASuccessThatFilesNothing() throws Exception {
            assertEquals(Outcome.FILED, service.file(groupRequest("Research ACME")).outcome());
            var again = service.file(groupRequest("research acme"));
            assertEquals(Outcome.DUPLICATE, again.outcome());
            assertTrue(again.success());
            assertEquals("PENDING", again.status());
            assertEquals(1, workspace().getBacklog().size());
        }

        @Test
        void aHumanAddedTaskWithTheSameSubjectBlocksTheHandoff() throws Exception {
            workspaces.mutate(TARGET, ws -> ws.getBacklog().addTask(new TaskItem("Research ACME", "mine", 1)));
            var result = service.file(groupRequest("research acme"));
            assertEquals(Outcome.DUPLICATE, result.outcome());
            assertEquals(1, workspace().getBacklog().size());
        }

        @Test
        void aPendingReviewItemBlocksAnIdenticalOne() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.REVIEW, null)), 0, 0);
            assertEquals(Outcome.QUEUED_FOR_REVIEW, service.file(groupRequest("same")).outcome());
            var again = service.file(groupRequest("same"));
            assertEquals(Outcome.DUPLICATE, again.outcome());
            assertEquals("PENDING_REVIEW", again.status());
            assertEquals(1, workspace().getHandoffs().inboxSize());
        }

        @Test
        void anExplicitKeyIsHonouredEvenWhenTheSubjectDiffers() throws Exception {
            assertEquals(Outcome.FILED, service.file(withDedupe(groupRequest("first wording"), "decision:gc-9:1:0")).outcome());
            assertEquals(Outcome.DUPLICATE, service.file(withDedupe(groupRequest("second wording"), "decision:gc-9:1:0")).outcome());
            assertEquals(1, workspace().getBacklog().size());
        }

        @Test
        void aRecentDeclineBlocksAndTheTombstoneExpires() throws Exception {
            String key = TeamHandoffService.defaultDedupeKey(groupRequest("Declined"), "Declined");
            workspaces.mutate(TARGET, ws -> ws.getHandoffs().tombstone(key, clock.instant().minus(Duration.ofDays(29))));
            assertEquals(Outcome.DECLINED_RECENTLY, service.file(groupRequest("Declined")).outcome());
            assertEquals(0, workspace().getBacklog().size());
            clock.now = clock.now.plus(Duration.ofDays(2));
            assertEquals(Outcome.FILED, service.file(groupRequest("Declined")).outcome());
        }
    }

    // --------------------------------------------------------------- landing

    @Nested
    class Landing {

        @Test
        void autoModeFilesAPendingTaskAndRecordsItsOrigin() throws Exception {
            var result = service.file(groupRequest("Research ACME"));
            assertEquals(Outcome.FILED, result.outcome());
            GroupWorkspace ws = workspace();
            TaskItem task = ws.getBacklog().getTasks().get(0);
            assertEquals("Research ACME", task.subject());
            assertEquals(6, task.priority());
            assertEquals("PENDING", task.status().name());
            HandoffOrigin origin = ws.getHandoffs().originOf(task.id());
            assertNotNull(origin, "provenance must survive the JSON round trip");
            assertEquals(result.handoffId(), origin.handoffId());
            assertEquals(SourceKind.GROUP, origin.sourceKind());
            assertEquals(SOURCE_GROUP, origin.sourceId());
            assertEquals("gc-1", origin.sourceConversationId());
            assertEquals("member-1", origin.sourceMemberAgentId());
            assertEquals(1, origin.hop());
            assertEquals(WorkspaceHandoffs.Intake.AUTO, origin.intake());
            assertEquals(0, ws.getHandoffs().inboxSize());
        }

        @Test
        void reviewModeQueuesInTheInboxAndNeverTheBacklog() throws Exception {
            // The invariant a cadence relies on: an inbox item is not executable work.
            var result = service.file(agentRequest("Warm lead"));
            assertEquals(Outcome.QUEUED_FOR_REVIEW, result.outcome());
            GroupWorkspace ws = workspace();
            assertEquals(0, ws.getBacklog().size());
            assertTrue(ws.getBacklog().findExecutableTasks().isEmpty(), "nothing a cadence could pull");
            assertEquals(1, ws.getHandoffs().inboxSize());
            var item = ws.getHandoffs().findInbox(result.handoffId());
            assertEquals("Warm lead", item.subject());
            assertEquals(5, item.priority(), "no priority given: the default");
            assertEquals(SourceKind.AGENT, item.origin().sourceKind());
            assertEquals(3, item.origin().sourceVersion());
            assertEquals(WorkspaceHandoffs.Intake.PENDING, item.origin().intake());
        }

        @Test
        void aFullInboxRefuses() {
            accept = new AcceptPolicy(List.of(new AcceptRule("agent:agent-1", IntakeMode.REVIEW, null)), 0, 2);
            assertTrue(service.file(agentRequest("a")).success());
            assertTrue(service.file(agentRequest("b")).success());
            assertEquals(Outcome.INBOX_FULL, service.file(agentRequest("c")).outcome());
        }

        @Test
        void aFullBacklogRefuses() throws Exception {
            workspaces.mutate(TARGET, ws -> {
                for (int i = 0; i < GroupWorkspace.MAX_BACKLOG_SIZE; i++) {
                    ws.getBacklog().addTask(new TaskItem("t" + i, "", 1));
                }
            });
            assertEquals(Outcome.BACKLOG_FULL, service.file(groupRequest("one more")).outcome());
        }
    }

    // ------------------------------------------------------------------- CAS

    @Nested
    class CompareAndSet {

        @Test
        void aLostCasIsRetriedAgainstTheFreshWorkspace() throws Exception {
            workspaces.beforeNextCas = () -> {
                try {
                    workspaces.mutate(TARGET, ws -> ws.getBacklog().addTask(new TaskItem("concurrent", "", 1)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            assertEquals(Outcome.FILED, service.file(groupRequest("mine")).outcome());
            assertEquals(2, workspace().getBacklog().size(), "the concurrent writer's task is not dropped");
        }

        @Test
        void aWriterThatFiledTheSameHandoffMeanwhileIsSeenOnRetry() throws Exception {
            // The re-validation inside the loop: without it the retry would blindly add
            // a second copy of a handoff another pod just filed.
            var request = withDedupe(groupRequest("Same decision"), "decision:gc-1:1:0");
            workspaces.beforeNextCas = () -> {
                try {
                    workspaces.mutate(TARGET, ws -> {
                        TaskItem t = ws.getBacklog().addTask(new TaskItem("Same decision (other wording)", "", 1));
                        ws.getHandoffs().putOrigin(t.id(),
                                new HandoffOrigin("h-other", SourceKind.GROUP, SOURCE_GROUP, null, "gc-1", null, Trigger.DECISION,
                                        null, null, null, 1, List.of(), "decision:gc-1:1:0", clock.instant(), WorkspaceHandoffs.Intake.AUTO, null));
                    });
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            assertEquals(Outcome.DUPLICATE, service.file(request).outcome());
            assertEquals(1, workspace().getBacklog().size());
        }

        @Test
        void aRetryRevalidatesCapacityToo() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("agent:agent-1", IntakeMode.REVIEW, null)), 0, 1);
            workspaces.beforeNextCas = () -> {
                try {
                    workspaces.mutate(TARGET, ws -> ws.getHandoffs().addInbox(new WorkspaceHandoffs.InboxItem("x", "someone else", "", 1, null)));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            assertEquals(Outcome.INBOX_FULL, service.file(agentRequest("mine")).outcome());
        }

        @Test
        void itGivesUpAfterThreeLostAttempts() throws Exception {
            var losses = new AtomicInteger();
            var racing = new HandoffFakes.WorkspaceStore() {
                @Override
                public boolean casRevision(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException {
                    losses.incrementAndGet();
                    return false;
                }
            };
            var svc = new TeamHandoffService(racing, groupStore, quotas, audit, meters, admissions, 3, Duration.ofDays(30), clock);
            var result = svc.file(groupRequest("x"));
            assertEquals(Outcome.CONFLICT, result.outcome());
            assertEquals(3, losses.get());
            assertEquals(0, quotas.total(), "nothing held after giving up");
        }

        @Test
        void aWorkspaceReadFailureFailsClosedAndReleases() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, 5)), 5, 0);
            workspaces.failReads = true;
            assertEquals(Outcome.UNAVAILABLE, service.file(groupRequest("x")).outcome());
            assertEquals(0, quotas.total());
        }

        @Test
        void aWorkspaceWriteFailureFailsClosedAndReleases() {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.AUTO, 5)), 5, 0);
            workspaces.failWrites = true;
            assertEquals(Outcome.UNAVAILABLE, service.file(groupRequest("x")).outcome());
            assertEquals(0, quotas.total());
        }
    }

    // ---------------------------------------------------------------- admission

    @Nested
    class Admission {

        private void install(HandoffAdmission... implementations) {
            when(admissions.isUnsatisfied()).thenReturn(false);
            when(admissions.iterator()).thenAnswer(inv -> List.of(implementations).iterator());
        }

        @Test
        void aRefusalBlocksTheHandoffAndGivesBackTheSlots() throws Exception {
            install((r, s, d, m) -> HandoffAdmission.Decision.refuse("contains a card number"));
            var result = service.file(groupRequest("x"));
            assertEquals(Outcome.REFUSED_ADMISSION, result.outcome());
            assertTrue(result.message().contains("contains a card number"));
            assertEquals(0, workspace().getBacklog().size());
            assertEquals(0, quotas.total());
        }

        @Test
        void aThrowingImplementationFailsClosed() {
            install((r, s, d, m) -> {
                throw new IllegalStateException("screen crashed");
            });
            assertEquals(Outcome.REFUSED_ADMISSION, service.file(groupRequest("x")).outcome());
        }

        @Test
        void redactionReplacesTheFiledText() throws Exception {
            install((r, s, d, m) -> HandoffAdmission.Decision.redact(s, "[redacted]"));
            assertEquals(Outcome.FILED, service.file(groupRequest("x")).outcome());
            assertEquals("[redacted]", workspace().getBacklog().getTasks().get(0).description());
        }

        @Test
        void redactedTextIsStillHeldToTheLimits() {
            install((r, s, d, m) -> HandoffAdmission.Decision.redact("s".repeat(500), null));
            assertEquals(Outcome.INVALID, service.file(groupRequest("x")).outcome());
        }

        @Test
        void everyImplementationSeesTheEngineDerivedSource() {
            var seen = new ArrayList<String>();
            install((r, s, d, m) -> {
                seen.add(r.sourceKey() + "->" + r.targetGroupId() + ":" + m);
                return HandoffAdmission.Decision.admit();
            });
            service.file(groupRequest("x"));
            assertEquals(List.of("group:" + SOURCE_GROUP + "->" + TARGET + ":AUTO"), seen);
        }
    }

    // ------------------------------------------------------- audit and metrics

    @Nested
    class Observability {

        @Test
        void aFiledHandoffIsAuditedWithoutTheFullDescription() {
            var result = service.file(groupRequest("Research ACME"));
            var captor = ArgumentCaptor.forClass(AuditEntry.class);
            verify(audit).submit(captor.capture());
            AuditEntry entry = captor.getValue();
            assertEquals("team.handoff", entry.taskId());
            assertEquals("group", entry.taskType());
            assertEquals("gc-1", entry.conversationId());
            assertEquals(SOURCE_GROUP, entry.agentId());
            assertEquals("user-1", entry.userId());
            var detail = entry.output();
            assertEquals(result.handoffId(), detail.get("handoffId"));
            assertEquals(TARGET, detail.get("targetGroupId"));
            assertEquals("AUTO", detail.get("mode"));
            assertEquals("FILED", detail.get("outcome"));
            assertEquals("Research ACME", detail.get("subject"));
            assertEquals(1, detail.get("hop"));
            assertEquals("TOOL", detail.get("trigger"));
            assertEquals(TeamHandoffService.sha256("details"), detail.get("descriptionSha256"));
            assertNotNull(detail.get("dedupeKey"));
            assertFalse(detail.containsValue("details"), "the description itself stays in the workspace");
        }

        @Test
        void aRefusalAfterConsentIsAuditedButAnUnconsentedProbeIsNot() {
            service.file(withLineage(groupRequest("loop"), 3, List.of()));
            verify(audit).submit(any(AuditEntry.class));
            accept = null;
            service.file(groupRequest("probe"));
            verify(audit).submit(any(AuditEntry.class));
        }

        @Test
        void aFailingAuditNeverFailsTheHandoff() {
            doThrow(new IllegalStateException("ledger down")).when(audit).submit(any());
            assertEquals(Outcome.FILED, service.file(groupRequest("x")).outcome());
        }

        @Test
        void theMetricCarriesBoundedTagsOnly() {
            service.file(groupRequest("x"));
            service.file(groupRequest("x"));
            assertEquals(1.0, meters.get("eddi_team_handoff_total").tags("trigger", "tool", "mode", "auto", "outcome", "filed").counter().count());
            assertEquals(1.0,
                    meters.get("eddi_team_handoff_total").tags("trigger", "tool", "mode", "auto", "outcome", "duplicate").counter().count());
            meters.get("eddi_team_handoff_total").counters().forEach(c -> c.getId().getTags().forEach(t -> {
                assertFalse(t.getValue().contains(TARGET) || t.getValue().contains(SOURCE_GROUP), "ids must never be tag values");
            }));
        }

        @Test
        void aStoreFailureIsCounted() {
            workspaces.failReads = true;
            service.file(groupRequest("x"));
            assertEquals(1.0, meters.get("eddi_team_handoff_store_failures_total").tag("operation", "read_workspace").counter().count());
        }
    }

    // -------------------------------------------------------- describeTarget

    @Nested
    class DescribeTarget {

        @Test
        void reportsAcceptanceModeAndSizesWithoutWriting() throws Exception {
            service.file(agentRequest("pending"));
            var d = service.describeTarget(TARGET, "agent:agent-1");
            assertTrue(d.accepting());
            assertEquals(IntakeMode.REVIEW, d.mode());
            assertEquals(1, d.inboxSize());
            assertEquals(0, d.backlogSize());
            assertFalse(service.describeTarget(TARGET, "agent:stranger").accepting());
            assertEquals(1, workspace().getHandoffs().inboxSize());
        }
    }

    // ------------------------------------------------------------------ bets

    @Nested
    class Bets {

        private HandoffRequest withBet(HandoffRequest r) {
            return new HandoffRequest(r.sourceKind(), r.sourceId(), r.sourceVersion(), r.sourceConversationId(), r.sourceMemberAgentId(), r.userId(),
                    r.targetGroupId(), r.subject(), r.description(), r.priority(), r.trigger(), r.decisionRef(), r.artifactId(), r.reference(),
                    r.hop(), r.lineage(), r.dedupeKey(), r.maxDescriptionChars(), r.runQuota(),
                    Bet.of("a launch post gets replies", "replies", ">= 5", "2026-12-01"));
        }

        @Test
        void anAutoFiledHandoffBecomesABetOnTheBacklog() throws Exception {
            assertTrue(service.file(withBet(groupRequest("Launch post"))).success());

            var task = workspace().getBacklog().getTasks().get(0);
            assertEquals("replies", task.bet().metric());
            assertEquals(Instant.parse("2026-12-01T00:00:00Z"), task.bet().reviewDate());
        }

        @Test
        void aReviewedHandoffKeepsItsBetInTheInbox() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("group:" + SOURCE_GROUP, IntakeMode.REVIEW, null)), 500, 100);

            assertEquals(Outcome.QUEUED_FOR_REVIEW, service.file(withBet(groupRequest("Launch post"))).outcome());

            assertEquals("a launch post gets replies", workspace().getHandoffs().getInbox().get(0).bet().hypothesis());
        }

        @Test
        void anOrdinaryHandoffIsNotABet() throws Exception {
            service.file(groupRequest("Plain"));
            assertNull(workspace().getBacklog().getTasks().get(0).bet());
        }
    }

    // ------------------------------------------------------------- provenance

    /**
     * A tool handoff carries text an agent wrote. The backlog task records that
     * agent as its creator, so a member of the receiving team that requires peer
     * verification is shown it only while that agent signs verifiably, as it is a
     * task an agent filed with its task tool.
     */
    @Nested
    class Provenance {

        @Test
        void aMembersHandoffIsFiledAsTheMemberThatCalledTheTool() throws Exception {
            assertTrue(service.file(groupRequest("From a member")).success());
            assertEquals("member-1", workspace().getBacklog().getTasks().get(0).createdByAgentId());
        }

        @Test
        void aStandaloneAgentsHandoffIsFiledAsThatAgent() throws Exception {
            accept = new AcceptPolicy(List.of(new AcceptRule("agent:agent-1", IntakeMode.AUTO, null)), 0, 0);
            assertTrue(service.file(agentRequest("From an agent")).success());
            assertEquals("agent-1", workspace().getBacklog().getTasks().get(0).createdByAgentId());
        }

        @Test
        void aDecisionHandoffNamesNoAgent() throws Exception {
            var r = groupRequest("From a decision");
            assertTrue(service.file(new HandoffRequest(SourceKind.GROUP, SOURCE_GROUP, null, "gc-1", null, "user-1", TARGET, r.subject(),
                    r.description(), 6, Trigger.DECISION, "decision-1", null, null, 0, List.of(), null, 0, null)).success());
            assertNull(workspace().getBacklog().getTasks().get(0).createdByAgentId());
        }

        /**
         * A receiving team whose records would not take the writing agent's text as its
         * (a member requires peer verification, the agent does not sign verifiably)
         * could never do an AUTO handoff: its cadence does not pull it. It waits in the
         * intake inbox instead, where a person vouches for it.
         */
        @Test
        void anAutoHandoffATeamCannotVerifyWaitsForReview() throws Exception {
            List<String> asked = new ArrayList<>();
            var verifying = new TeamHandoffService(workspaces, groupStore, quotas, audit, meters, admissions, 3, Duration.ofDays(30), clock,
                    (config, agentId) -> {
                        asked.add(agentId);
                        return false;
                    });

            var result = verifying.file(groupRequest("From an unsigned member"));

            assertEquals(Outcome.QUEUED_FOR_REVIEW, result.outcome());
            assertEquals(0, workspace().getBacklog().size(), "nothing its cadence would pull and fail");
            assertEquals(1, workspace().getHandoffs().inboxSize());
            assertEquals(List.of("member-1"), asked, "decided by the agent that wrote the text");
        }

        @Test
        void anAutoHandoffATeamCanVerifyIsFiled() throws Exception {
            var trusting = new TeamHandoffService(workspaces, groupStore, quotas, audit, meters, admissions, 3, Duration.ofDays(30), clock,
                    (config, agentId) -> true);

            assertEquals(Outcome.FILED, trusting.file(groupRequest("From a signing member")).outcome());
            assertEquals(1, workspace().getBacklog().size());
        }
    }

}
