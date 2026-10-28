/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.modules.llm.guardrails.impl.PiiRedactionGuardrail;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E1b: {@code onExhausted: deliver-best} on an output {@code reprompt}
 * guardrail — the best-rated attempt is delivered with a {@code warn}, and is
 * still screened by the guardrails after the exhausted one.
 */
@DisplayName("GuardrailService — onExhausted deliver-best (E1b)")
class GuardrailServiceDeliverBestTest {

    private static final GuardrailContext CONTEXT = new GuardrailContext(GuardrailPhase.OUTPUT, "task1", "0123456789abcdef01234567",
            "0123456789abcdef76543210", null, List.of());

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final List<GuardrailOutcome> outcomes = new ArrayList<>();

    /** A guardrail whose verdict is a function of the text. */
    private record Scripted(String type, Function<String, GuardrailVerdict> verdict) implements ContentGuardrail {
        @Override
        public Set<GuardrailPhase> phases() {
            return Set.of(GuardrailPhase.INPUT, GuardrailPhase.OUTPUT);
        }

        @Override
        public GuardrailVerdict evaluate(String text, GuardrailContext context) {
            return verdict.apply(text);
        }
    }

    /**
     * A grading guardrail: the issue codes are the words of the text that start
     * with {@code BAD_}.
     */
    private static Scripted grading() {
        return new Scripted("judge", text -> {
            List<String> codes = Arrays.stream(text.split("\\s+")).filter(w -> w.startsWith("BAD_")).toList();
            return codes.isEmpty() ? GuardrailVerdict.allow() : GuardrailVerdict.issues(codes, "judge_failed:" + String.join(",", codes), "Fix it.");
        });
    }

    private static Scripted flagging(String type, String marker) {
        return new Scripted(type, text -> text.contains(marker) ? GuardrailVerdict.violation("found_" + type) : GuardrailVerdict.allow());
    }

    private static GuardrailConfig config(String type, String action) {
        var config = new GuardrailConfig();
        config.setType(type);
        config.setAction(action);
        config.setBlockMessage("Blocked by " + type + ".");
        return config;
    }

    private static GuardrailConfig deliverBest(String type, int maxRetries) {
        var config = config(type, "reprompt");
        config.setMaxRetries(maxRetries);
        config.setOnExhausted("deliver-best");
        return config;
    }

    /** A reprompter that answers the scripted texts in order. */
    private static Reprompter answers(String... texts) {
        Deque<String> queue = new ArrayDeque<>(List.of(texts));
        return (rejected, corrective) -> new Reprompter.Attempt(queue.removeFirst(), Map.of());
    }

    private GuardrailService service(ContentGuardrail... guardrails) {
        return new GuardrailService(List.of(guardrails), meterRegistry);
    }

    private GuardrailOutcome last() {
        return outcomes.get(outcomes.size() - 1);
    }

    @Nested
    @DisplayName("selection")
    class Selection {

        @Test
        @DisplayName("the attempt with the fewest issue codes is delivered, with a warn :delivered_best outcome carrying the reprompt count")
        void fewestIssues() throws Exception {
            var result = service(grading()).runOutput(List.of(deliverBest("judge", 2)), "one BAD_A BAD_B", answers("two BAD_A", "three BAD_A BAD_C"),
                    CONTEXT, outcomes::add);

            assertFalse(result.isBlocked());
            assertEquals("two BAD_A", result.text());
            assertEquals(2, result.reprompts().size());
            GuardrailOutcome delivered = last();
            assertEquals(GuardrailAction.WARN, delivered.action());
            assertEquals("judge_failed:BAD_A" + GuardrailService.DELIVERED_BEST_SUFFIX, delivered.reason());
            assertEquals(2, delivered.retryCount());
            assertEquals(1.0, meterRegistry.counter(GuardrailService.METRIC_TRIGGERED, "phase", "output", "type", "judge", "action", "warn").count());
            assertEquals(2.0,
                    meterRegistry.counter(GuardrailService.METRIC_TRIGGERED, "phase", "output", "type", "judge", "action", "reprompt").count());
        }

        @Test
        @DisplayName("on a tie the later attempt wins; for a binary guardrail that is the last attempt")
        void tiesGoToTheLater() throws Exception {
            var judged = service(grading()).runOutput(List.of(deliverBest("judge", 1)), "first BAD_A", answers("second BAD_B"), CONTEXT,
                    outcomes::add);
            assertEquals("second BAD_B", judged.text());

            outcomes.clear();
            var binary = service(flagging("json-object", "prose")).runOutput(List.of(deliverBest("json-object", 2)), "prose 1",
                    answers("prose 2", "prose 3"), CONTEXT, outcomes::add);
            assertFalse(binary.isBlocked());
            assertEquals("prose 3", binary.text());
            assertEquals("found_json-object" + GuardrailService.DELIVERED_BEST_SUFFIX, last().reason());
        }

        @Test
        @DisplayName("without deliver-best the exhausted reprompt still blocks (default unchanged)")
        void defaultBlocks() throws Exception {
            var cfg = deliverBest("judge", 1);
            cfg.setOnExhausted(null);
            var result = service(grading()).runOutput(List.of(cfg), "BAD_A", answers("BAD_B"), CONTEXT, outcomes::add);
            assertTrue(result.isBlocked());
            assertEquals("Blocked by judge.", result.text());

            cfg.setOnExhausted("block");
            outcomes.clear();
            assertTrue(service(grading()).runOutput(List.of(cfg), "BAD_A", answers("BAD_B"), CONTEXT, outcomes::add).isBlocked());
        }

        @Test
        @DisplayName("an unknown onExhausted value fails safe: blocks")
        void unknownBlocks() throws Exception {
            var cfg = deliverBest("judge", 0);
            cfg.setOnExhausted("deliver-whatever");
            assertTrue(service(grading()).runOutput(List.of(cfg), "BAD_A", null, CONTEXT, outcomes::add).isBlocked());
        }

        @Test
        @DisplayName("no reprompter (or maxRetries 0): the only attempt is delivered with a warn")
        void noReprompter() throws Exception {
            var result = service(grading()).runOutput(List.of(deliverBest("judge", 2)), "only BAD_A", null, CONTEXT, outcomes::add);
            assertFalse(result.isBlocked());
            assertEquals("only BAD_A", result.text());
            assertEquals(0, last().retryCount());
        }

        @Test
        @DisplayName("a reprompt that cannot start (turn out of time) delivers the best so far")
        void repromptCannotStart() throws Exception {
            var result = service(grading()).runOutput(List.of(deliverBest("judge", 2)), "first BAD_A", (r, c) -> null, CONTEXT, outcomes::add);
            assertFalse(result.isBlocked());
            assertEquals("first BAD_A", result.text());
        }

        @Test
        @DisplayName("a judge that could not decide still blocks (failMode closed) — exhaustion is not failure")
        void undecidedStillBlocks() throws Exception {
            var undecided = new Scripted("judge", text -> {
                throw new GuardrailUndecidedException("judge_unparseable", 0.0);
            });
            var result = service(undecided).runOutput(List.of(deliverBest("judge", 1)), "x", answers("y"), CONTEXT, outcomes::add);
            assertTrue(result.isBlocked());
            assertEquals("judge_unparseable", result.blocked().reason());
        }
    }

    @Nested
    @DisplayName("the delivered attempt is re-screened")
    class Rescreen {

        @Test
        @DisplayName("judge deliver-best + pii-redaction: the delivered text is redacted")
        void redactionStillApplies() throws Exception {
            // pii-redaction runs after the judge: the attempt the judge rated is
            // unredacted.
            var configs = List.of(deliverBest("judge", 1), config(PiiRedactionGuardrail.TYPE, "redact"));
            var result = service(grading(), new PiiRedactionGuardrail()).runOutput(configs, "mail jane@example.org BAD_A",
                    answers("mail john@example.org BAD_A BAD_B"), CONTEXT, outcomes::add);

            assertFalse(result.isBlocked());
            assertEquals("mail [REDACTED:email] BAD_A", result.text());
            assertEquals(GuardrailAction.REDACT, last().action());
        }

        @Test
        @DisplayName("judge deliver-best + a blocking guardrail that flags the best attempt: blocked")
        void blockingGuardrailBlocks() throws Exception {
            var configs = List.of(deliverBest("judge", 1), config("safety", "block"));
            // The safety check only looks at what the judge did not stop; the best attempt
            // (fewer issues) is the unsafe one, and the re-screen must catch it.
            var safety = flagging("safety", "UNSAFE");
            var result = service(grading(), safety).runOutput(configs, "UNSAFE BAD_A", answers("safe BAD_A BAD_B"), CONTEXT, outcomes::add);
            // first attempt: judge flags (1 issue) → reprompt; second: judge flags 2 →
            // exhausted;
            // best is the first (1 issue), which the safety check blocks.
            assertTrue(result.isBlocked());
            assertEquals("Blocked by safety.", result.text());
            assertEquals("safety", result.blocked().type());
            // The attempt was never delivered, so nothing may record it as delivered —
            // and the block is counted once, not once when collected and again when
            // emitted.
            assertTrue(outcomes.stream().noneMatch(o -> o.reason().endsWith(GuardrailService.DELIVERED_BEST_SUFFIX)), outcomes.toString());
            assertEquals(GuardrailAction.BLOCK, last().action());
            assertEquals(1.0,
                    meterRegistry.counter(GuardrailService.METRIC_TRIGGERED, "phase", "output", "type", "safety", "action", "block").count());
            assertEquals(0.0, meterRegistry.counter(GuardrailService.METRIC_TRIGGERED, "phase", "output", "type", "judge", "action", "warn").count());
        }

        @Test
        @DisplayName("another reprompt guardrail that flags the best attempt blocks — no further reprompts")
        void otherRepromptBlocks() throws Exception {
            var configs = List.of(deliverBest("judge", 1), config("json-object", "reprompt"));
            List<String> correctives = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>(List.of("prose BAD_A BAD_B"));
            Reprompter reprompter = (rejected, corrective) -> {
                correctives.add(corrective);
                return new Reprompter.Attempt(queue.removeFirst(), Map.of());
            };
            var result = service(grading(), flagging("json-object", "prose")).runOutput(configs, "prose BAD_A", reprompter, CONTEXT, outcomes::add);
            assertTrue(result.isBlocked());
            assertEquals("json-object", result.blocked().type());
            assertEquals(1, correctives.size(), "only the judge's reprompt was sent");
        }

        @Test
        @DisplayName("a guardrail before the exhausted one screened the attempt already: it is not run, recorded or billed again")
        void earlierGuardrailsAreNotRunAgain() throws Exception {
            List<String> seen = new ArrayList<>();
            var note = new Scripted("note", text -> {
                seen.add(text);
                return text.contains("BAD_") ? GuardrailVerdict.violation("found_note").withCost(0.01) : GuardrailVerdict.allow();
            });
            var configs = List.of(config("note", "warn"), deliverBest("judge", 1));
            var result = service(note, grading()).runOutput(configs, "first BAD_A", answers("second BAD_A BAD_B"), CONTEXT, outcomes::add);

            assertFalse(result.isBlocked());
            assertEquals("first BAD_A", result.text());
            // Once per attempt (two attempts), never a third time for the delivered one.
            assertEquals(List.of("first BAD_A", "second BAD_A BAD_B"), seen);
            assertEquals(2.0, meterRegistry.counter(GuardrailService.METRIC_TRIGGERED, "phase", "output", "type", "note", "action", "warn").count());
            assertEquals(0.02, result.costUsd(), 1e-9);
        }

        @Test
        @DisplayName("a fail-open redact that gave up part-way still applies its partial rewrite to the delivered attempt")
        void partialFindingStillApplies() throws Exception {
            // A pattern list that timed out after its first match: undecided, with the
            // rewrite it managed. Failing open, the main pass applies that rewrite; so must
            // the re-screen of a deliver-best attempt, or the attempt ships unredacted.
            var partial = new Scripted("regex-filter", text -> {
                throw new GuardrailUndecidedException("pattern_timeout", 0.0,
                        GuardrailVerdict.rewrite(text.replace("secret", "[REDACTED]"), "regex_filter"));
            });
            var redact = config("regex-filter", "redact");
            redact.setParameters(Map.of(GuardrailService.PARAM_FAIL_MODE, "open"));
            var configs = List.of(deliverBest("judge", 0), redact);
            var result = service(grading(), partial).runOutput(configs, "the secret BAD_A", null, CONTEXT, outcomes::add);

            assertFalse(result.isBlocked());
            assertEquals("the [REDACTED] BAD_A", result.text());
            assertEquals(GuardrailAction.REDACT, last().action());
        }

        @Test
        @DisplayName("the exhausted guardrail itself is skipped, and a warn guardrail is still recorded")
        void exhaustedSkippedWarnRecorded() throws Exception {
            var configs = List.of(deliverBest("judge", 0), config("note", "warn"));
            var result = service(grading(), flagging("note", "BAD_")).runOutput(configs, "x BAD_A", null, CONTEXT, outcomes::add);
            assertFalse(result.isBlocked());
            assertEquals("x BAD_A", result.text());
            assertEquals(List.of("judge:warn", "note:warn"), outcomes.stream().map(o -> o.type() + ":" + o.action().label()).toList());
        }
    }

    @Nested
    @DisplayName("deploy time")
    class DeployTime {

        @Test
        @DisplayName("deliver-best is refused on any action but reprompt, on input guardrails, and unknown values are refused")
        void refusals() {
            var svc = service(grading());
            var onBlock = config("judge", "block");
            onBlock.setOnExhausted("deliver-best");
            assertEquals(1, svc.validate("outputGuardrails", List.of(onBlock)).size());

            var unknown = deliverBest("judge", 1);
            unknown.setOnExhausted("retry-forever");
            assertTrue(svc.validate("outputGuardrails", List.of(unknown)).get(0).contains("'block' or 'deliver-best'"));

            assertEquals(1, svc.validate(GuardrailService.LIST_INPUT, List.of(deliverBest("judge", 1))).size());

            var explicitBlock = config("judge", "warn");
            explicitBlock.setOnExhausted("block");
            assertTrue(svc.validate("outputGuardrails", List.of(deliverBest("judge", 1), explicitBlock)).isEmpty());
            assertTrue(svc.validate("outputGuardrails", List.of(onBlock)).get(0).startsWith("outputGuardrails[0]: "));
        }

        @Test
        @DisplayName("deliver-best on a classifier is a warning, not a refusal")
        void classifierWarning() {
            var svc = service(flagging("classifier", "x"));
            var cfg = deliverBest("classifier", 1);
            assertTrue(svc.validate("outputGuardrails", List.of(cfg)).isEmpty());
            List<String> warnings = svc.warnings("outputGuardrails", List.of(cfg));
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("classifier"), warnings.get(0));
            cfg.setOnExhausted(null);
            assertTrue(svc.warnings("outputGuardrails", List.of(cfg)).isEmpty());
        }
    }
}
