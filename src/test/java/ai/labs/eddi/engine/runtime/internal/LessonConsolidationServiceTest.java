/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LessonConsolidationConfig;
import ai.labs.eddi.configs.groups.validation.ServiceJudgeVaultGrants;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.groups.lessons.ILessonHistoryStore;
import ai.labs.eddi.engine.groups.lessons.LessonHistoryEntry;
import ai.labs.eddi.engine.groups.lessons.LessonHistoryEntry.Action;
import ai.labs.eddi.engine.groups.lessons.LessonService;
import ai.labs.eddi.engine.groups.outcomes.IOutcomeEventStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent;
import ai.labs.eddi.engine.runtime.internal.LessonConsolidationService.Result;
import ai.labs.eddi.engine.runtime.internal.LessonConsolidationService.Status;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import ai.labs.eddi.modules.llm.impl.SummarizationService.SummarizationResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LessonConsolidationService")
class LessonConsolidationServiceTest {

    private static final String GROUP = "g1";
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    private LessonService lessons;
    private IUserMemoryStore memory;
    private ILessonHistoryStore history;
    private IOutcomeEventStore outcomes;
    private SummarizationService llm;
    private TenantQuotaService quota;
    private LessonConsolidationService service;

    private LessonConsolidationConfig consolidation;
    private final List<UserMemoryEntry> live = new ArrayList<>();
    private final List<OutcomeEvent> events = new ArrayList<>();

    private static UserMemoryEntry lesson(int n) {
        return new UserMemoryEntry("id-" + n, "group:" + GROUP, "retro:k" + n, "Lesson number " + n, "context", Visibility.group, "retro",
                List.of(GROUP),
                "gc-1", false, 0, NOW.minusSeconds(10_000 - n), NOW.minusSeconds(5_000 - n));
    }

    private static OutcomeEvent outcome(String id) {
        return new OutcomeEvent(id, GROUP, OutcomeEvent.SubjectType.TASK, "t1", "reply_received", "replies", 0.0, "count", "nobody answered", "crm",
                "svc",
                NOW.minusSeconds(100), NOW.minusSeconds(100), null);
    }

    @BeforeEach
    void setUp() throws Exception {
        lessons = mock(LessonService.class);
        memory = mock(IUserMemoryStore.class);
        history = mock(ILessonHistoryStore.class);
        outcomes = mock(IOutcomeEventStore.class);
        llm = mock(SummarizationService.class);
        quota = mock(TenantQuotaService.class);
        when(quota.getDefaultTenantId()).thenReturn("default");
        when(quota.checkCostBudget(any())).thenReturn(QuotaCheckResult.OK);
        consolidation = new LessonConsolidationConfig("anthropic", "claude-x", Map.of("apiKey", "k"), 1.0, 1.0, 5.0, 6, 40, 30, 20);
        for (int i = 1; i <= 8; i++) {
            live.add(lesson(i));
        }
        events.add(outcome("o1"));
        when(lessons.configOf(GROUP)).thenAnswer(i -> new LessonConfig(null, null, null, consolidation));
        when(lessons.lessonsOf(eq(GROUP), anyInt())).thenAnswer(i -> List.copyOf(live));
        when(outcomes.listByGroup(eq(GROUP), any(), anyInt())).thenAnswer(i -> List.copyOf(events));
        when(history.insertIfAbsent(any())).thenReturn(true);
        when(memory.replaceIfUnchanged(anyString(), any(), any())).thenReturn(true);
        when(memory.deleteEntryIfUnchanged(anyString(), any())).thenReturn(true);
        service = new LessonConsolidationService(lessons, memory, history, outcomes, llm, quota, new SimpleMeterRegistry());
        service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private void modelAnswers(String json) {
        when(llm.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new SummarizationResult(json, 500, 100));
    }

    private static final String MERGE_1_2 = "{\"merge\":[{\"keep\":\"retro:k1\",\"drop\":[\"retro:k2\"],\"text\":\"Merged lesson one and two\"}],\"retire\":[]}";
    private static final String RETIRE_3 = "{\"merge\":[],\"retire\":[{\"key\":\"retro:k3\",\"reason\":\"a later outcome showed it wrong\",\"evidence\":[\"o1\"]}]}";

    // === gating ===

    @Test
    void notConfiguredIsASkipNotAnError() {
        when(lessons.configOf(GROUP)).thenReturn(null);
        Result r = service.run(GROUP, false);
        assertEquals(Status.SKIPPED, r.status());
        assertTrue(r.message().contains("not configured"), r.message());
        verify(llm, never()).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void withoutAModelItFailsAndSaysWhy() {
        consolidation = new LessonConsolidationConfig(null, null, null, null, null, null, null, null, null, null);
        Result r = service.run(GROUP, false);
        assertEquals(Status.FAILED, r.status());
        assertTrue(r.message().contains("llmProvider"), r.message());
        assertFalse(r.isSuccess());
    }

    @Test
    void tooFewLessonsMakeNoCall() {
        live.subList(5, 8).clear();
        Result r = service.run(GROUP, false);
        assertEquals(Status.SKIPPED, r.status());
        verify(llm, never()).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void aSpentTenantBudgetMakesNoCall() {
        when(quota.checkCostBudget(any())).thenReturn(QuotaCheckResult.denied("over budget"));
        Result r = service.run(GROUP, false);
        assertEquals(Status.SKIPPED, r.status());
        verify(llm, never()).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void aMissingGroupInTheScheduleMetadataFails() {
        assertEquals(Status.FAILED, service.processScheduledFire(Map.of()).status());
        assertEquals(Status.FAILED, service.processScheduledFire(null).status());
    }

    // === the cost cap ===

    @Test
    @DisplayName("a run whose worst case exceeds maxCostPerRun makes no call at all")
    void theCapIsCheckedBeforeTheCall() throws Exception {
        consolidation = new LessonConsolidationConfig("anthropic", "claude-x", Map.of(), 0.0001, 1000.0, 1000.0, 6, 40, 30, 20);
        modelAnswers(MERGE_1_2);

        Result r = service.run(GROUP, false);

        assertEquals(Status.SKIPPED, r.status());
        assertTrue(r.message().contains("maxCostPerRun"), r.message());
        verify(llm, never()).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(memory, never()).replaceIfUnchanged(anyString(), any(), any());
    }

    @Test
    @DisplayName("lessons are dropped until the worst case fits, and the call then goes out with fewer")
    void theSetShrinksToFitTheCap() {
        for (int i = 9; i <= 40; i++) {
            live.add(lesson(i));
        }
        // A cap that fits a small set but not forty lessons.
        consolidation = new LessonConsolidationConfig("anthropic", "claude-x", Map.of(), 0.15, 100.0, 100.0, 6, 40, 30, 20);
        modelAnswers("{\"merge\":[],\"retire\":[]}");

        Result r = service.run(GROUP, false);

        assertEquals(Status.COMPLETED, r.status());
        assertTrue(r.lessonsConsidered() < 40 && r.lessonsConsidered() >= 6, "considered " + r.lessonsConsidered());
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(llm).summarizeWithUsage(content.capture(), anyString(), anyString(), anyString(), anyMap());
        assertTrue(LessonConsolidationService.worstCase(content.getValue(), consolidation) <= 0.15);
    }

    // === what the model is given ===

    @Test
    void theModelIsToldTheInputIsDataAndGetsLessonsAndOutcomes() {
        modelAnswers("{}");
        service.run(GROUP, false);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> instructions = ArgumentCaptor.forClass(String.class);
        verify(llm).summarizeWithUsage(content.capture(), instructions.capture(), eq("anthropic"), eq("claude-x"), eq(Map.of("apiKey", "k")));
        assertTrue(instructions.getValue().contains("DATA"), "the input is labelled as data");
        assertTrue(content.getValue().contains("retro:k1") && content.getValue().contains("\"id\":\"o1\""), content.getValue());
    }

    // === applying ===

    @Test
    @DisplayName("a merge writes history first, then the merged lesson, then drops the duplicate — conditionally")
    void mergeOrder() throws Exception {
        modelAnswers(MERGE_1_2);

        Result r = service.run(GROUP, false);

        assertEquals(Status.COMPLETED, r.status());
        assertEquals(1, r.appliedCount());
        InOrder order = inOrder(history, memory);
        order.verify(history, times(2)).insertIfAbsent(any());
        order.verify(memory).replaceIfUnchanged(eq("id-1"), any(), eq(live.get(0).updatedAt()));
        order.verify(memory).deleteEntryIfUnchanged("id-2", live.get(1).updatedAt());
        ArgumentCaptor<UserMemoryEntry> merged = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(memory).replaceIfUnchanged(anyString(), merged.capture(), any());
        assertEquals("Merged lesson one and two", merged.getValue().value());
        assertEquals("retro:k1", merged.getValue().key());
        assertEquals("retro", merged.getValue().sourceAgentId(), "the idempotency identity of a retro lesson is kept");
        assertEquals(Visibility.group, merged.getValue().visibility());
        verify(memory, never()).deleteEntryIfUnchanged(eq("id-1"), any());
    }

    @Test
    void theHistoryKeepsTheTextAndSaysWhere() {
        modelAnswers(MERGE_1_2);
        service.run(GROUP, false);
        ArgumentCaptor<LessonHistoryEntry> rows = ArgumentCaptor.forClass(LessonHistoryEntry.class);
        verify(history, times(2)).insertIfAbsent(rows.capture());
        LessonHistoryEntry dropped = rows.getAllValues().stream().filter(e -> e.lessonKey().equals("retro:k2")).findFirst().orElseThrow();
        assertEquals("Lesson number 2", dropped.lessonValue());
        assertEquals(Action.MERGED, dropped.action());
        assertEquals("retro:k1", dropped.replacedByKey());
        assertNotNull(dropped.runId());
        assertEquals(NOW, dropped.at());
    }

    @Test
    void aRetirementCitingAShownOutcomeIsAppliedAndKept() throws Exception {
        modelAnswers(RETIRE_3);

        Result r = service.run(GROUP, false);

        assertEquals(1, r.appliedCount());
        ArgumentCaptor<LessonHistoryEntry> row = ArgumentCaptor.forClass(LessonHistoryEntry.class);
        verify(history).insertIfAbsent(row.capture());
        assertEquals(Action.RETIRED, row.getValue().action());
        assertEquals(List.of("o1"), row.getValue().evidence());
        assertEquals("Lesson number 3", row.getValue().lessonValue());
        verify(memory).deleteEntryIfUnchanged("id-3", live.get(2).updatedAt());
    }

    @Test
    void aRetirementWithoutEvidenceOrWithAnOutcomeNeverShownIsRefused() throws Exception {
        modelAnswers("{\"retire\":[{\"key\":\"retro:k3\",\"reason\":\"feels wrong\"},"
                + "{\"key\":\"retro:k4\",\"reason\":\"x\",\"evidence\":[\"o-not-shown\"]}]}");

        Result r = service.run(GROUP, false);

        assertEquals(0, r.appliedCount());
        assertEquals(2, r.actions().size());
        assertTrue(r.actions().get(0).note().contains("cite the outcome"), r.actions().get(0).note());
        assertTrue(r.actions().get(1).note().contains("not shown"), r.actions().get(1).note());
        verify(memory, never()).deleteEntryIfUnchanged(anyString(), any());
        verify(history, never()).insertIfAbsent(any());
    }

    @Test
    void unknownKeysAndLessonsUsedTwiceAreRefused() throws Exception {
        modelAnswers("{\"merge\":[{\"keep\":\"retro:k1\",\"drop\":[\"retro:k2\"],\"text\":\"a\"},"
                + "{\"keep\":\"retro:k3\",\"drop\":[\"retro:k2\"],\"text\":\"b\"},"
                + "{\"keep\":\"retro:nope\",\"drop\":[\"retro:k4\"],\"text\":\"c\"},"
                + "{\"keep\":\"retro:k5\",\"drop\":[\"retro:k5\"],\"text\":\"d\"},"
                + "{\"keep\":\"retro:k6\",\"drop\":[],\"text\":\"e\"}],"
                + "\"retire\":[{\"key\":\"retro:k1\",\"reason\":\"r\",\"evidence\":[\"o1\"]}]}");

        Result r = service.run(GROUP, false);

        assertEquals(1, r.appliedCount(), "only the first merge is valid");
        assertEquals(5, r.actions().stream().filter(a -> a.note() != null).count());
        verify(memory, times(1)).replaceIfUnchanged(anyString(), any(), any());
    }

    @Test
    void aMergedTextThatIsTooLongOrEmptyIsRefused() throws Exception {
        modelAnswers("{\"merge\":[{\"keep\":\"retro:k1\",\"drop\":[\"retro:k2\"],\"text\":\"" + "x".repeat(1001) + "\"},"
                + "{\"keep\":\"retro:k3\",\"drop\":[\"retro:k4\"],\"text\":\"  \"}]}");
        assertEquals(0, service.run(GROUP, false).appliedCount());
        verify(memory, never()).replaceIfUnchanged(anyString(), any(), any());
    }

    @Test
    void oneRunAppliesAtMostTwentyActions() throws Exception {
        live.clear();
        for (int i = 1; i <= 100; i++) {
            live.add(lesson(i));
        }
        StringBuilder json = new StringBuilder("{\"merge\":[");
        for (int i = 1; i <= 90; i += 2) {
            json.append(i > 1 ? "," : "").append("{\"keep\":\"retro:k").append(i).append("\",\"drop\":[\"retro:k").append(i + 1)
                    .append("\"],\"text\":\"m").append(i)
                    .append("\"}");
        }
        modelAnswers(json.append("]}").toString());

        Result r = service.run(GROUP, false);

        assertEquals(LessonConsolidationService.MAX_ACTIONS_PER_RUN, r.actions().size());
        assertEquals(LessonConsolidationService.MAX_ACTIONS_PER_RUN, r.appliedCount());
    }

    @Test
    @DisplayName("an action touching a lesson rewritten during the model call is skipped and the newer text kept")
    void concurrentWritesWin() throws Exception {
        modelAnswers(MERGE_1_2);
        // The lesson changes between the read and the apply.
        when(lessons.lessonsOf(eq(GROUP), anyInt())).thenAnswer(i -> List.copyOf(live)).thenAnswer(i -> {
            List<UserMemoryEntry> changed = new ArrayList<>(live);
            UserMemoryEntry k2 = changed.get(1);
            changed.set(1, new UserMemoryEntry(k2.id(), k2.userId(), k2.key(), "Rewritten by a conversation", k2.category(), k2.visibility(),
                    k2.sourceAgentId(), k2.groupIds(), k2.sourceConversationId(), false, 0, k2.createdAt(), NOW));
            return changed;
        });

        Result r = service.run(GROUP, false);

        assertEquals(0, r.appliedCount());
        assertTrue(r.actions().get(0).note().contains("written during the run"), r.actions().get(0).note());
        verify(memory, never()).replaceIfUnchanged(anyString(), any(), any());
        verify(history, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("a kept lesson rewritten after the check loses nothing: the merge is skipped and no duplicate is dropped")
    void aMergeLosingTheConditionalWriteIsSkipped() throws Exception {
        modelAnswers(MERGE_1_2);
        when(memory.replaceIfUnchanged(anyString(), any(), any())).thenReturn(false);

        Result r = service.run(GROUP, false);

        assertEquals(Status.COMPLETED, r.status());
        assertEquals(0, r.appliedCount());
        assertTrue(r.actions().get(0).note().contains("written during the run"), r.actions().get(0).note());
        verify(memory, never()).deleteEntryIfUnchanged(anyString(), any());
        verify(memory, never()).upsert(any());
    }

    @Test
    @DisplayName("a duplicate rewritten after the check is kept, and the merge is not reported as fully applied")
    void aMergeWhoseDuplicateCannotBeDroppedIsPartial() throws Exception {
        modelAnswers(MERGE_1_2);
        when(memory.deleteEntryIfUnchanged(anyString(), any())).thenReturn(false);

        Result r = service.run(GROUP, false);

        assertEquals(Status.COMPLETED, r.status());
        assertEquals(0, r.appliedCount());
        assertTrue(r.actions().get(0).note().contains("retro:k2"), r.actions().get(0).note());
        assertTrue(r.actions().get(0).note().contains("partially applied"), r.actions().get(0).note());
    }

    @Test
    @DisplayName("a retirement whose conditional delete loses is skipped, not counted as applied")
    void aRetirementLosingTheConditionalDeleteIsSkipped() throws Exception {
        modelAnswers(RETIRE_3);
        when(memory.deleteEntryIfUnchanged(anyString(), any())).thenReturn(false);

        Result r = service.run(GROUP, false);

        assertEquals(Status.COMPLETED, r.status());
        assertEquals(0, r.appliedCount());
        assertTrue(r.actions().get(0).note().contains("written during the run"), r.actions().get(0).note());
    }

    @Test
    @DisplayName("an ungranted vault reference in the consolidation parameters fails the run before any model call")
    void anUngrantedVaultReferenceFailsBeforeTheModelCall() {
        var grants = mock(ServiceJudgeVaultGrants.class);
        when(grants.problems(eq("lessonConfig.consolidation.parameters"), any()))
                .thenReturn(List.of("lessonConfig.consolidation.parameters.apiKey needs a grant"));
        service.vaultGrants = grants;
        modelAnswers(MERGE_1_2);

        Result r = service.run(GROUP, false);

        assertEquals(Status.FAILED, r.status());
        assertTrue(r.message().contains("apiKey needs a grant"), r.message());
        verify(llm, never()).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("a lesson deleted during the model call is a skipped action, not a failed run")
    void aLessonRemovedMeanwhileIsSkipped() throws Exception {
        modelAnswers(MERGE_1_2);
        when(lessons.lessonsOf(eq(GROUP), anyInt())).thenAnswer(i -> List.copyOf(live)).thenAnswer(i -> live.subList(1, live.size()));

        Result r = service.run(GROUP, false);

        assertEquals(Status.COMPLETED, r.status());
        assertEquals(0, r.appliedCount());
        assertTrue(r.actions().get(0).note().contains("written during the run"), r.actions().get(0).note());
        verify(memory, never()).replaceIfUnchanged(anyString(), any(), any());
    }

    @Test
    void aDryRunProposesAndChangesNothing() throws Exception {
        modelAnswers(MERGE_1_2);

        Result r = service.run(GROUP, true);

        assertTrue(r.dryRun());
        assertEquals(1, r.actions().size());
        assertEquals(0, r.appliedCount());
        verify(memory, never()).replaceIfUnchanged(anyString(), any(), any());
        verify(memory, never()).deleteEntryIfUnchanged(anyString(), any());
        verify(history, never()).insertIfAbsent(any());
        assertTrue(r.estimatedCost() > 0, "a dry run still spends and says how much");
    }

    @Test
    void anAnswerThatIsNotJsonChangesNothing() throws Exception {
        modelAnswers("I think lesson 1 and 2 are the same.");
        Result r = service.run(GROUP, false);
        assertEquals(Status.COMPLETED, r.status());
        assertEquals(0, r.actions().size());
        verify(memory, never()).replaceIfUnchanged(anyString(), any(), any());
    }

    @Test
    void aFencedAnswerWithProseAroundItIsStillRead() {
        modelAnswers("Here you go:\n```json\n" + MERGE_1_2 + "\n```\nHope that helps");
        assertEquals(1, service.run(GROUP, true).actions().size());
    }

    @Test
    void historyIsTrimmedAfterARun() {
        modelAnswers(MERGE_1_2);
        service.run(GROUP, false);
        verify(history).deleteOldestBeyond(GROUP, LessonConsolidationService.MAX_HISTORY_PER_GROUP);
    }

    // === failures ===

    @Test
    void aTransientModelFailureSkipsSoItCannotDeadLetterTheSchedule() throws Exception {
        when(llm.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("timeout", new SocketTimeoutException("slow")));
        Result r = service.run(GROUP, false);
        assertEquals(Status.SKIPPED, r.status());
        verify(memory, never()).replaceIfUnchanged(anyString(), any(), any());
    }

    @Test
    void aPermanentModelFailureFailsTheRunAndNamesTheModel() {
        when(llm.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalArgumentException("401 invalid api key"));
        Result r = service.run(GROUP, false);
        assertEquals(Status.FAILED, r.status());
        assertTrue(r.message().contains("anthropic/claude-x"), r.message());
    }

    @Test
    void theCallIsChargedToTheTenantAtConfiguredPrices() {
        modelAnswers("{}");
        service.run(GROUP, false);
        verify(quota).recordCost(eq("default"), doubleThat(d -> d > 0));
    }

    @Test
    void anUnpricedModelChargesNothing() {
        consolidation = new LessonConsolidationConfig("anthropic", "claude-x", Map.of(), 5.0, null, null, 6, 40, 30, 20);
        modelAnswers("{}");
        service.run(GROUP, false);
        verify(quota, never()).recordCost(anyString(), anyDouble());
    }

    @Test
    void aSecondRunOfTheSameTeamWhileOneIsRunningIsSkipped() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(llm.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), anyMap())).thenAnswer(i -> {
            started.countDown();
            release.await();
            return new SummarizationResult("{}", 10, 10);
        });
        var first = CompletableFuture.supplyAsync(() -> service.run(GROUP, true));
        started.await();

        Result second = service.run(GROUP, true);
        release.countDown();

        assertEquals(Status.SKIPPED, second.status());
        assertTrue(second.message().contains("already running"), second.message());
        assertEquals(Status.COMPLETED, first.get().status());
        assertEquals(Status.COMPLETED, service.run(GROUP, true).status(), "the guard is released");
    }

    @Test
    void theScheduleMarkerIsRecognised() {
        assertTrue(LessonConsolidationService.isLessonConsolidationSchedule(
                Map.of(LessonConsolidationService.METADATA_TYPE_KEY, LessonConsolidationService.METADATA_TYPE_CONSOLIDATION)));
        assertFalse(LessonConsolidationService
                .isLessonConsolidationSchedule(Map.of(LessonConsolidationService.METADATA_TYPE_KEY, "dream_consolidation")));
        assertFalse(LessonConsolidationService.isLessonConsolidationSchedule(null));
    }
}
