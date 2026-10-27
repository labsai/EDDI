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
import ai.labs.eddi.configs.groups.mongo.AgentGroupStore;
import ai.labs.eddi.engine.internal.groups.PhaseOutcome.PhaseExitSignal;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E2 {@code convergence.mode: "VERDICT"}: a repeating phase ends once the
 * moderator's review of the latest repeat passes, next to the similarity mode,
 * which stays the default.
 */
@DisplayName("convergence.mode VERDICT (E2)")
class ConvergenceVerdictModeTest {

    private static final String MODERATOR = "moderator";
    private static final String QUESTION = "Draft the launch announcement";

    private MemberTurnExecutor memberTurnExecutor;
    private PhaseExecutionEngine engine;
    private AgentGroupConfiguration config;
    private GroupConversation gc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        memberTurnExecutor = mock(MemberTurnExecutor.class);
        engine = new PhaseExecutionEngine(memberTurnExecutor, mock(GroupContextBuilder.class), Executors.newVirtualThreadPerTaskExecutor(),
                new CallerIdentityContext(null, null));
        config = new AgentGroupConfiguration();
        config.setModeratorAgentId(MODERATOR);
        config.setMembers(List.of(new GroupMember("writer", "writer", 1, null)));
        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setOriginalQuestion(QUESTION);
    }

    private static DiscussionPhase phase(ConvergenceConfig convergence) {
        return new DiscussionPhase("Drafts", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, AgentGroupConfiguration.ContextScope.FULL, false,
                null, 4, false, convergence);
    }

    private static ProtocolConfig protocol() {
        return new ProtocolConfig(180, MemberFailurePolicy.SKIP, 0, MemberUnavailablePolicy.SKIP, 50, null, CostPolicy.SYNTHESIZE_NOW);
    }

    private static List<TranscriptEntry> round(String content) {
        return List.of(new TranscriptEntry("writer", "writer", content, 0, "Drafts", TranscriptEntryType.OPINION, Instant.now(), null, null));
    }

    private void judgeSays(String reply) throws Exception {
        when(memberTurnExecutor.executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(new TranscriptEntry(MODERATOR, "Convergence Judge", reply, 0, "Drafts", TranscriptEntryType.OPINION, Instant.now(),
                        null, null));
    }

    private PhaseOutcome check(DiscussionPhase phase, int repeat, List<TranscriptEntry> previous, AtomicInteger turns, int maxTurns) {
        return engine.checkConvergence(gc, config, phase, protocol(), 0, repeat, config.getMembers(), round("draft v" + (repeat + 1)), previous,
                null, turns, maxTurns);
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("a config saved before 6.6 is SIMILARITY with minRepeats 2")
        void legacyIsSimilarity() throws Exception {
            var convergence = mapper.readValue("{\"enabled\":true}", ConvergenceConfig.class);

            assertEquals(ConvergenceConfig.MODE_SIMILARITY, convergence.mode());
            assertEquals(2, convergence.minRepeats());
            assertFalse(convergence.isVerdictMode());
        }

        @Test
        @DisplayName("VERDICT is case-insensitive, and its minRepeats may be 1")
        void verdictModeParses() throws Exception {
            var convergence = mapper.readValue("{\"enabled\":true,\"mode\":\"verdict\"}", ConvergenceConfig.class);

            assertTrue(convergence.isVerdictMode());
            assertEquals(1, convergence.minRepeats());
            String json = mapper.writeValueAsString(convergence);
            assertTrue(json.contains("\"mode\":\"VERDICT\""), json);
            assertFalse(json.contains("verdictMode"), json);
        }

        @Test
        @DisplayName("save time refuses an unknown mode")
        void unknownModeRefused() {
            var group = new AgentGroupConfiguration();
            group.setName("g");
            group.setPhases(List.of(phase(new ConvergenceConfig(true, 2, 0.8, null, "vibes"))));

            assertTrue(AgentGroupStore.memberAndLimitProblems(group).stream().anyMatch(p -> p.contains("convergence.mode")));
            group.setPhases(List.of(phase(new ConvergenceConfig(true, 1, 0.8, null, "VERDICT"))));
            assertTrue(AgentGroupStore.memberAndLimitProblems(group).stream().noneMatch(p -> p.contains("convergence.mode")));
        }
    }

    @Nested
    @DisplayName("the verdict parse never ends a phase on doubt")
    class Parse {

        @Test
        @DisplayName("a JSON boolean true passes, with the feedback as the reason")
        void passes() {
            var verdict = ConvergenceDetector.parseVerdict("```json\n{\"passed\": true, \"feedback\": \"complete\"}\n```");

            assertTrue(verdict.converged());
            assertEquals(1.0, verdict.agreementScore());
            assertTrue(verdict.summary().contains("complete"));
        }

        @Test
        @DisplayName("false, the string \"true\", prose and no output all keep repeating")
        void doubtKeepsRepeating() {
            assertFalse(ConvergenceDetector.parseVerdict("{\"passed\": false, \"feedback\": \"no date\"}").converged());
            assertFalse(ConvergenceDetector.parseVerdict("{\"passed\": \"true\"}").converged());
            assertFalse(ConvergenceDetector.parseVerdict("Looks good to me!").converged());
            assertFalse(ConvergenceDetector.parseVerdict(null).converged());
            assertEquals(-1, ConvergenceDetector.parseVerdict("Looks good to me!").agreementScore());
        }

        @Test
        @DisplayName("the question and the round are data: neither is template-substituted into the other")
        void inputIsConcatenated() {
            String input = ConvergenceDetector.buildVerdictInput("What about {currentRound}?", round("v1 {question}"));

            assertTrue(input.contains("What about {currentRound}?"));
            assertTrue(input.contains("v1 {question}"));
        }
    }

    @Nested
    @DisplayName("checkConvergence")
    class Check {

        @Test
        @DisplayName("VERDICT: a passing review ends the phase after the first repeat — no previous round needed")
        void verdictStopsEarly() throws Exception {
            judgeSays("{\"passed\": true, \"feedback\": \"ready to publish\"}");
            var turns = new AtomicInteger();

            var outcome = check(phase(new ConvergenceConfig(true, 0, 0.8, null, "VERDICT")), 0, null, turns, 50);

            assertEquals(PhaseExitSignal.END_REPEATS, outcome.signal());
            assertEquals(1, turns.get(), "the judge call is a counted turn");
            var input = ArgumentCaptor.forClass(String.class);
            verify(memberTurnExecutor).executeAgentTurn(any(), any(), input.capture(), any(), anyInt(), any(), any(), any(), any(),
                    eq(PhaseExecutionEngine.JUDGE_CONVERSATION_KEY));
            assertTrue(input.getValue().contains(QUESTION));
            assertTrue(gc.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.CONVERGENCE && e.content().contains("passed")));
        }

        @Test
        @DisplayName("VERDICT: a failing review keeps the phase going")
        void verdictFailContinues() throws Exception {
            judgeSays("{\"passed\": false, \"feedback\": \"no launch date\"}");

            var outcome = check(phase(new ConvergenceConfig(true, 0, 0.8, null, "VERDICT")), 0, null, new AtomicInteger(), 50);

            assertEquals(PhaseExitSignal.CONTINUE, outcome.signal());
        }

        @Test
        @DisplayName("default (SIMILARITY): the first repeat is never judged, exactly as before")
        void similarityDefaultUnchanged() throws Exception {
            judgeSays("{\"passed\": true}");

            var outcome = check(phase(new ConvergenceConfig(true)), 0, null, new AtomicInteger(), 50);

            assertEquals(PhaseExitSignal.CONTINUE, outcome.signal());
            verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("default (SIMILARITY): a VERDICT-shaped reply does not end the phase — the score decides")
        void similarityIgnoresPassed() throws Exception {
            judgeSays("{\"passed\": true}");

            var outcome = check(phase(new ConvergenceConfig(true)), 1, round("draft v1"), new AtomicInteger(), 50);

            assertEquals(PhaseExitSignal.CONTINUE, outcome.signal());
        }

        @Test
        @DisplayName("VERDICT respects maxTurns: no judge call when the budget is spent")
        void verdictRespectsMaxTurns() throws Exception {
            judgeSays("{\"passed\": true}");

            var outcome = check(phase(new ConvergenceConfig(true, 0, 0.8, null, "VERDICT")), 0, null, new AtomicInteger(50), 50);

            assertEquals(PhaseExitSignal.CONTINUE, outcome.signal());
            verify(memberTurnExecutor, never()).executeAgentTurn(any(), any(), anyString(), any(), anyInt(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("VERDICT respects minRepeats when it is set")
        void verdictRespectsMinRepeats() throws Exception {
            judgeSays("{\"passed\": true}");

            var outcome = check(phase(new ConvergenceConfig(true, 2, 0.8, null, "VERDICT")), 0, null, new AtomicInteger(), 50);

            assertEquals(PhaseExitSignal.CONTINUE, outcome.signal());
        }
    }
}
