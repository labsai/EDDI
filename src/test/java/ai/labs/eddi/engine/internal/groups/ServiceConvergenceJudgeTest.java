/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ConvergenceConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.ServiceJudgeConfig;
import ai.labs.eddi.configs.groups.validation.ServiceJudgeVaultGrants;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.CostUpdatedEvent;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import ai.labs.eddi.modules.llm.impl.SummarizationService.SummarizationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code convergence.judge: "SERVICE"}: a direct model call instead of a
 * moderator-agent turn, priced onto the discussion's ledger, and never a reason
 * to end a phase on doubt.
 */
@DisplayName("SERVICE convergence judge")
class ServiceConvergenceJudgeTest {

    private static final String MODERATOR = "moderator";
    private static final ServiceJudgeConfig SERVICE = new ServiceJudgeConfig("openai", "gpt-4o-mini",
            Map.of("apiKey", "${vault:openai-key}"), 2.0, 4.0, null);

    private SummarizationService summarizer;
    private MemberTurnExecutor memberTurnExecutor;
    private SimpleMeterRegistry meters;
    private PhaseExecutionEngine engine;
    private AgentGroupConfiguration config;
    private GroupConversation gc;

    @BeforeEach
    void setUp() {
        summarizer = mock(SummarizationService.class);
        memberTurnExecutor = mock(MemberTurnExecutor.class);
        meters = new SimpleMeterRegistry();
        engine = new PhaseExecutionEngine(memberTurnExecutor, mock(GroupContextBuilder.class), Executors.newVirtualThreadPerTaskExecutor(),
                new CallerIdentityContext(null, null));
        engine.setServiceJudge(new ServiceConvergenceJudge(() -> summarizer, meters));
        config = new AgentGroupConfiguration();
        config.setMembers(List.of(new GroupMember("writer", "writer", 1, null)));
        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setOriginalQuestion("Pick a launch date");
    }

    private static DiscussionPhase phase(ConvergenceConfig convergence) {
        return new DiscussionPhase("Rounds", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL, false,
                null, 4, false, convergence);
    }

    private static ConvergenceConfig serviceConvergence(ServiceJudgeConfig service) {
        return new ConvergenceConfig(true, 2, 0.8, ConvergenceConfig.JUDGE_SERVICE, null, service);
    }

    private static ProtocolConfig protocol() {
        return new ProtocolConfig(180, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP, 50, null, CostPolicy.SYNTHESIZE_NOW);
    }

    private static List<TranscriptEntry> round(String content) {
        return List.of(new TranscriptEntry("writer", "writer", content, 0, "Rounds", TranscriptEntryType.OPINION, Instant.now(), null, null));
    }

    private PhaseOutcome check(ConvergenceConfig convergence, int repeat, GroupDiscussionEventListener listener) {
        return engine.checkConvergence(gc, config, phase(convergence), protocol(), 0, repeat, config.getMembers(), round("v" + (repeat + 1)),
                round("v" + repeat), listener, new AtomicInteger(), 50);
    }

    private void judgeReplies(String reply, int in, int out) {
        when(summarizer.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(
                new SummarizationResult(reply, in, out));
    }

    @Nested
    @DisplayName("runs in the engine")
    class InTheEngine {

        @Test
        @DisplayName("converges without any moderator configured, and runs no agent turn")
        void convergesWithoutModerator() throws Exception {
            judgeReplies("{\"agreementScore\":0.95,\"converged\":true,\"summary\":\"same position\"}", 100, 10);

            PhaseOutcome outcome = check(serviceConvergence(SERVICE), 1, null);

            assertFalse(outcome.isContinue(), "a passing service verdict ends the repeats");
            verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("SERVICE without a service block still runs the moderator agent, as before")
        void fallsBackToModerator() throws Exception {
            config.setModeratorAgentId(MODERATOR);
            when(memberTurnExecutor.executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(), any()))
                    .thenReturn(new TranscriptEntry(MODERATOR, "Convergence Judge", "{\"agreementScore\":0.9}", 0, "Rounds",
                            TranscriptEntryType.OPINION, Instant.now(), null, null));

            PhaseOutcome outcome = check(serviceConvergence(null), 1, null);

            assertFalse(outcome.isContinue());
            verify(summarizer, never()).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any(), any());
            assertEquals(1, meters.get(ServiceConvergenceJudge.METRIC_DURATION).tag("outcome", "fallback_moderator").timers().size());
        }

        @Test
        @DisplayName("a judge that throws never ends the phase, and its error text stays out of the transcript")
        void failureIsNotConverged() {
            when(summarizer.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any(), any()))
                    .thenThrow(new IllegalStateException("401 for key sk-should-not-leak"));

            PhaseOutcome outcome = check(serviceConvergence(SERVICE), 1, null);

            assertTrue(outcome.isContinue());
            var entry = gc.getTranscript().getLast();
            assertEquals(TranscriptEntryType.CONVERGENCE, entry.type());
            assertFalse(entry.content().contains("sk-should-not-leak"), entry.content());
            assertTrue(gc.getMemberCosts().isEmpty(), "nothing is billed for a call that failed");
        }
    }

    @Nested
    @DisplayName("spend")
    class Spend {

        @Test
        @DisplayName("books the call on the ledger under a key unique per phase and repeat, and announces it")
        void booksAndAnnounces() {
            judgeReplies("{\"agreementScore\":0.2}", 1_000_000, 500_000);
            var listener = mock(GroupDiscussionEventListener.class);

            check(serviceConvergence(SERVICE), 1, listener);
            check(serviceConvergence(SERVICE), 2, listener);

            assertEquals(4.0, gc.getMemberCosts().get("system:judge:1:0:1"), 1e-9, "1M in at $2 + 0.5M out at $4");
            assertEquals(4.0, gc.getMemberCosts().get("system:judge:1:0:2"), 1e-9);
            assertEquals(8.0, gc.getTotalCost(), 1e-9, "two checks, two charges");
            var events = ArgumentCaptor.forClass(CostUpdatedEvent.class);
            verify(listener, times(2)).onCostUpdated(events.capture());
            assertEquals("system:judge:1:0:1", events.getAllValues().getFirst().attributionKey());
            assertEquals(ServiceConvergenceJudge.DISPLAY_NAME, events.getAllValues().getFirst().displayName());
        }

        @Test
        @DisplayName("a continuation round does not overwrite the earlier round's charge")
        void continuationRoundKeepsEarlierSpend() {
            judgeReplies("{\"agreementScore\":0.2}", 1_000_000, 500_000);

            check(serviceConvergence(SERVICE), 1, null);
            gc.setRound(2);
            check(serviceConvergence(SERVICE), 1, null);

            assertEquals(4.0, gc.getMemberCosts().get("system:judge:1:0:1"), 1e-9);
            assertEquals(4.0, gc.getMemberCosts().get("system:judge:2:0:1"), 1e-9);
            assertEquals(8.0, gc.getTotalCost(), 1e-9, "the same phase and repeat in round 2 adds, it does not replace");
        }

        @Test
        @DisplayName("the judge call is a single attempt, so one check cannot outlast its timeout")
        void singleAttempt() {
            judgeReplies("{\"agreementScore\":0.2}", 1, 1);

            check(serviceConvergence(SERVICE), 1, null);

            var retry = ArgumentCaptor.forClass(RetryConfiguration.class);
            verify(summarizer).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any(), retry.capture());
            assertEquals(1, retry.getValue().getMaxAttempts());
        }

        @Test
        @DisplayName("a call that outlasts timeoutMs, model setup included, is abandoned as not converged")
        void overrunIsAbandoned() {
            var release = new CountDownLatch(1);
            when(summarizer.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any(), any())).thenAnswer(inv -> {
                release.await();
                return new SummarizationResult("{\"agreementScore\":0.9,\"converged\":true}", 1, 1);
            });
            var service = new ServiceJudgeConfig("openai", "gpt-4o-mini", null, null, null, 1);
            var convergence = serviceConvergence(service);
            var judge = new ServiceConvergenceJudge(() -> summarizer, meters);

            long start = System.nanoTime();
            var verdict = judge.judge(gc, phase(convergence), 0, 1, convergence, "MEMBER {text}",
                    out -> ConvergenceDetector.parseJudgeVerdict(out, 0.8), null);
            release.countDown();

            assertTrue(verdict.isPresent());
            assertFalse(verdict.get().converged());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "returned at the deadline, not when the call finished");
            assertEquals(1, meters.get(ServiceConvergenceJudge.METRIC_DURATION).tag("outcome", "error").timers().size());
        }

        @Test
        @DisplayName("a grant narrowed after the save fails the judge, never resolves the secret, and never throws")
        void narrowedGrantFailsTheJudge() {
            var grants = mock(ServiceJudgeVaultGrants.class);
            when(grants.firstProblem(any())).thenReturn(Optional.of("service.parameters.apiKey references vault secret x"));
            var judge = new ServiceConvergenceJudge(() -> summarizer, meters, () -> grants);
            var convergence = serviceConvergence(SERVICE);

            var verdict = judge.judge(gc, phase(convergence), 0, 1, convergence, "MEMBER {text}",
                    out -> ConvergenceDetector.parseJudgeVerdict(out, 0.8), null);

            assertTrue(verdict.isPresent());
            assertFalse(verdict.get().converged());
            verify(summarizer, never()).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any(), any());
            assertEquals(1, meters.get(ServiceConvergenceJudge.METRIC_DURATION).tag("outcome", "error").timers().size());
            assertTrue(gc.getMemberCosts() == null || gc.getMemberCosts().isEmpty());
        }

        @Test
        @DisplayName("a still-granted secret lets the judge run")
        void grantedSecretRuns() {
            var grants = mock(ServiceJudgeVaultGrants.class);
            when(grants.firstProblem(any())).thenReturn(Optional.empty());
            judgeReplies("{\"agreementScore\":0.2}", 1, 1);
            var judge = new ServiceConvergenceJudge(() -> summarizer, meters, () -> grants);
            var convergence = serviceConvergence(SERVICE);

            judge.judge(gc, phase(convergence), 0, 1, convergence, "MEMBER {text}", out -> ConvergenceDetector.parseJudgeVerdict(out, 0.8),
                    null);

            verify(summarizer).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any(), any());
        }

        @Test
        @DisplayName("an unparseable reply was still paid for, and does not converge")
        void unparseableIsBilled() {
            judgeReplies("I think they mostly agree.", 1_000_000, 0);

            PhaseOutcome outcome = check(serviceConvergence(SERVICE), 1, null);

            assertTrue(outcome.isContinue());
            assertEquals(2.0, gc.getMemberCosts().get("system:judge:1:0:1"), 1e-9);
            assertEquals(1, meters.get(ServiceConvergenceJudge.METRIC_DURATION).tag("outcome", "unparseable").timers().size());
        }

        @Test
        @DisplayName("an unpriced judge books nothing")
        void unpricedIsFree() {
            judgeReplies("{\"agreementScore\":0.2}", 1_000, 1_000);
            var listener = mock(GroupDiscussionEventListener.class);

            check(serviceConvergence(new ServiceJudgeConfig("openai", "gpt-4o-mini", null, null, null, null)), 1, listener);

            assertTrue(gc.getMemberCosts().isEmpty());
            verify(listener, never()).onCostUpdated(any());
        }
    }

    @Nested
    @DisplayName("the call")
    class TheCall {

        @Test
        @DisplayName("sends the fixed instruction as system text and the judge prompt as data, with the timeout forced")
        void callShape() {
            judgeReplies("{\"agreementScore\":0.2}", 1, 1);
            var judge = new ServiceConvergenceJudge(() -> summarizer, null);
            var service = new ServiceJudgeConfig("openai", "gpt-4o-mini", Map.of("timeout", "999999", "temperature", "0"), null, null,
                    5_000);

            var verdict = judge.judge(gc, phase(serviceConvergence(service)), 0, 1, serviceConvergence(service), "MEMBER {text}",
                    out -> ConvergenceDetector.parseJudgeVerdict(out, 0.8), null);

            assertTrue(verdict.isPresent());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
            verify(summarizer).summarizeWithUsage(eq("MEMBER {text}"), eq(ServiceConvergenceJudge.JUDGE_INSTRUCTIONS), eq("openai"),
                    eq("gpt-4o-mini"), params.capture(), any(RetryConfiguration.class));
            assertEquals("5000", params.getValue().get("timeout"), "the judge's own bound wins over the parameter map");
            assertEquals("0", params.getValue().get("temperature"));
        }

        @Test
        @DisplayName("MODERATOR judge is not handled")
        void moderatorNotHandled() {
            var judge = new ServiceConvergenceJudge(() -> summarizer, null);
            var moderator = new ConvergenceConfig(true, 2, 0.8, ConvergenceConfig.JUDGE_MODERATOR, null, SERVICE);

            assertTrue(judge.judge(gc, phase(moderator), 0, 1, moderator, "x", ConvergenceDetector::parseVerdict, null).isEmpty());
        }

        @Test
        @DisplayName("no summarization service means not converged rather than an exception")
        void noService() {
            var judge = new ServiceConvergenceJudge(() -> null, null);

            var verdict = judge.judge(gc, phase(serviceConvergence(SERVICE)), 0, 1, serviceConvergence(SERVICE), "x",
                    ConvergenceDetector::parseVerdict, null);

            assertTrue(verdict.isPresent());
            assertFalse(verdict.get().converged());
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("the service block round-trips through JSON and is normalized")
        void roundTrip() throws Exception {
            var convergence = mapper.readValue("""
                    {"enabled":true,"judge":"service","service":{"llmProvider":" openai ","llmModel":"gpt-4o-mini",
                     "parameters":{"apiKey":"${vault:k}"},"inputPricePer1M":-1,"outputPricePer1M":0.6,"timeoutMs":999999}}""",
                    ConvergenceConfig.class);

            assertTrue(convergence.usesServiceJudge());
            assertEquals("openai", convergence.service().llmProvider());
            assertNull(convergence.service().inputPricePer1M(), "a negative price is unpriced");
            assertEquals(ServiceJudgeConfig.MAX_TIMEOUT_MS, convergence.service().timeoutMs());
            String json = mapper.writeValueAsString(convergence);
            assertFalse(json.contains("usesServiceJudge"), json);
            assertFalse(json.contains("configured"), json);
            assertEquals(convergence, mapper.readValue(json, ConvergenceConfig.class));
        }

        @Test
        @DisplayName("a config saved before the block existed has no service and does not use the service judge")
        void legacy() throws Exception {
            var convergence = mapper.readValue("{\"enabled\":true,\"judge\":\"SERVICE\"}", ConvergenceConfig.class);

            assertNull(convergence.service());
            assertFalse(convergence.usesServiceJudge());
            assertEquals(ServiceJudgeConfig.DEFAULT_TIMEOUT_MS,
                    new ServiceJudgeConfig("p", "m", null, null, null, 0).timeoutMs());
            assertNotNull(new ServiceJudgeConfig(null, " ", null, null, null, null).parameters());
            assertFalse(new ServiceJudgeConfig("p", " ", null, null, null, null).isConfigured());
        }
    }

    @Test
    @DisplayName("JudgeCallPricing prices with the configured per-1M rates")
    void pricing() {
        assertEquals(0.75, JudgeCallPricing.cost("openai", "m", 0.5, 1.0, 1_000_000, 250_000), 1e-9);
        assertEquals(0.0, JudgeCallPricing.cost("openai", "m", null, null, 1_000_000, 1_000_000), 1e-9);
    }
}
