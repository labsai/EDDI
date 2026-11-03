/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import ai.labs.eddi.modules.llm.guardrails.ToolCallGuardrailScreener.Decision;
import ai.labs.eddi.modules.llm.guardrails.ToolCallGuardrailScreener.Mode;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolCallGuardrailScreener")
class ToolCallGuardrailScreenerTest {

    private static final GuardrailContext CONTEXT = new GuardrailContext(null, "task1", "conv", "agent", null, List.of());

    private static class Scripted implements ContentGuardrail {
        private final String type;
        private final Set<GuardrailPhase> phases;
        private final Function<String, GuardrailVerdict> verdict;
        private final boolean screensHistory;
        final AtomicInteger calls = new AtomicInteger();

        Scripted(String type, Set<GuardrailPhase> phases, boolean screensHistory, Function<String, GuardrailVerdict> verdict) {
            this.type = type;
            this.phases = phases;
            this.verdict = verdict;
            this.screensHistory = screensHistory;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public Set<GuardrailPhase> phases() {
            return phases;
        }

        @Override
        public boolean screensHistory() {
            return screensHistory;
        }

        @Override
        public GuardrailVerdict evaluate(String text, GuardrailContext context) {
            calls.incrementAndGet();
            assertEquals(GuardrailPhase.TOOL_CALL, context.phase());
            return verdict.apply(text);
        }
    }

    private static final Set<GuardrailPhase> TOOL = Set.of(GuardrailPhase.TOOL_CALL);

    private final List<GuardrailOutcome> outcomes = new ArrayList<>();

    private static GuardrailConfig config(String type, String action) {
        var config = new GuardrailConfig();
        config.setType(type);
        config.setAction(action);
        return config;
    }

    private static GuardrailConfig config(String type, String action, String failMode) {
        var config = config(type, action);
        Map<String, String> params = new HashMap<>();
        params.put("failMode", failMode);
        config.setParameters(params);
        return config;
    }

    private static ToolCallGuardrailScreener screener(ContentGuardrail... guardrails) {
        return new ToolCallGuardrailScreener(new GuardrailService(List.of(guardrails), new SimpleMeterRegistry()));
    }

    private ToolCallGuardrailScreener.Result screen(ToolCallGuardrailScreener screener, GuardrailConfig config, String text, int reprompts,
                                                    Mode mode) {
        return screener.screen(config, text, CONTEXT, reprompts, mode, null, outcomes::add);
    }

    private static Scripted rewriting(String type, String from, String to) {
        return new Scripted(type, TOOL, true,
                text -> text.contains(from) ? GuardrailVerdict.rewrite(text.replace(from, to), "rewrote") : GuardrailVerdict.allow());
    }

    private static Scripted flagging(String type, String marker) {
        return new Scripted(type, TOOL, true, text -> text.contains(marker) ? GuardrailVerdict.violation("found") : GuardrailVerdict.allow());
    }

    private static Scripted throwing(String type, GuardrailUndecidedException e) {
        return new Scripted(type, TOOL, true, text -> {
            throw e;
        });
    }

    @Nested
    @DisplayName("actions on the live path")
    class Live {

        @Test
        @DisplayName("block: the call is refused with the block message, the rewrite kept for the transcript")
        void block() {
            var s = screener(rewriting("pii", "secret", "[X]"));
            var config = config("pii", "block");
            config.setBlockMessage("Remove the secret.");
            var result = screen(s, config, "my secret", 0, Mode.LIVE);
            assertEquals(Decision.BLOCK, result.decision());
            assertEquals("Remove the secret.", result.message());
            assertEquals("my [X]", result.text());
            assertEquals(GuardrailAction.BLOCK, outcomes.get(0).action());
            assertEquals(GuardrailPhase.TOOL_CALL, outcomes.get(0).phase());
        }

        @Test
        @DisplayName("redact: the field is rewritten; a violation without a rewrite substitutes the block message")
        void redact() {
            var s = screener(rewriting("pii", "secret", "[X]"), flagging("flag", "bad"));
            assertEquals(new ToolCallGuardrailScreener.Result(Decision.REWRITE, "my [X]", "rewrote", List.of(), null, 0.0, false),
                    screen(s, config("pii", "redact"), "my secret", 0, Mode.LIVE));
            var flagged = config("flag", "redact");
            flagged.setBlockMessage("[removed]");
            var result = screen(s, flagged, "bad", 0, Mode.LIVE);
            assertEquals(Decision.REWRITE, result.decision());
            assertEquals("[removed]", result.text());
        }

        @Test
        @DisplayName("reprompt: refused with the corrective text, counted; blocked once maxRetries are spent")
        void reprompt() {
            var s = screener(new Scripted("judge", TOOL, false,
                    text -> GuardrailVerdict.issues(List.of("UNAPPROVED_CLAIM"), "judge_issues", "Remove every unapproved claim.")));
            var config = config("judge", "reprompt");
            config.setMaxRetries(1);
            var first = screen(s, config, "claims", 0, Mode.LIVE);
            assertEquals(Decision.REPROMPT, first.decision());
            assertEquals("Remove every unapproved claim.", first.message());
            assertEquals(List.of("UNAPPROVED_CLAIM"), first.issues());
            assertEquals(1, outcomes.get(0).retryCount());

            var second = screen(s, config, "claims", 1, Mode.LIVE);
            assertEquals(Decision.BLOCK, second.decision());
            assertEquals(GuardrailAction.BLOCK, outcomes.get(1).action());
        }

        @Test
        @DisplayName("reprompt without a hint: the default tool-call corrective text with the reason")
        void repromptDefault() {
            var s = screener(flagging("flag", "x"));
            var result = screen(s, config("flag", "reprompt"), "x", 0, Mode.LIVE);
            assertEquals(ToolCallGuardrailScreener.DEFAULT_CORRECTIVE.replace("{reason}", "found"), result.message());
        }

        @Test
        @DisplayName("warn: recorded, the call is allowed")
        void warn() {
            var s = screener(flagging("flag", "x"));
            var result = screen(s, config("flag", "warn"), "x", 0, Mode.LIVE);
            assertEquals(Decision.ALLOW, result.decision());
            assertEquals(GuardrailAction.WARN, outcomes.get(0).action());
        }

        @Test
        @DisplayName("nothing found: allowed, nothing recorded")
        void clean() {
            var s = screener(flagging("flag", "x"));
            assertEquals(Decision.ALLOW, screen(s, config("flag", "block"), "clean", 0, Mode.LIVE).decision());
            assertTrue(outcomes.isEmpty());
        }

        @Test
        @DisplayName("a type that does not run on tool-call arguments is not evaluated")
        void wrongPhase() {
            var input = new Scripted("in", Set.of(GuardrailPhase.INPUT), true, t -> GuardrailVerdict.violation("x"));
            var s = screener(input);
            assertFalse(s.supports(config("in", "block")));
            assertFalse(s.supports(config("unknown", "block")));
            assertEquals(Decision.ALLOW, screen(s, config("in", "block"), "x", 0, Mode.LIVE).decision());
            assertEquals(0, input.calls.get());
        }
    }

    @Nested
    @DisplayName("failMode")
    class FailMode {

        @Test
        @DisplayName("block and reprompt fail closed by default")
        void blockFailsClosed() {
            var s = screener(throwing("t", new GuardrailUndecidedException("svc_down", 0.0)));
            var result = screen(s, config("t", "block"), "x", 0, Mode.LIVE);
            assertEquals(Decision.BLOCK, result.decision());
            assertTrue(result.undecided());
            assertEquals("svc_down", result.reason());
            assertEquals(Decision.BLOCK, screen(s, config("t", "reprompt"), "x", 0, Mode.LIVE).decision());
        }

        @Test
        @DisplayName("redact and warn fail open by default; failMode closed refuses")
        void redactFailsOpen() {
            var s = screener(throwing("t", new GuardrailUndecidedException("svc_down", 0.0)));
            assertEquals(Decision.ALLOW, screen(s, config("t", "redact"), "x", 0, Mode.LIVE).decision());
            assertEquals(GuardrailAction.WARN, outcomes.get(0).action());
            assertEquals(Decision.BLOCK, screen(s, config("t", "redact", "closed"), "x", 0, Mode.LIVE).decision());
            assertEquals(Decision.ALLOW, screen(s, config("t", "block", "open"), "x", 0, Mode.LIVE).decision());
        }

        @Test
        @DisplayName("failing open, a partial rewrite is still applied")
        void partialRewriteApplied() {
            var s = screener(throwing("t", new GuardrailUndecidedException("pattern_timeout", 0.0, GuardrailVerdict.rewrite("half [X]", "partial"))));
            var result = screen(s, config("t", "redact"), "half secret", 0, Mode.LIVE);
            assertEquals(Decision.REWRITE, result.decision());
            assertEquals("half [X]", result.text());
        }

        @Test
        @DisplayName("undecided arguments follow the entry's failMode")
        void undecidedArguments() {
            var s = screener(flagging("flag", "x"));
            assertEquals(Decision.BLOCK, s.undecided(config("flag", "block"), "arguments_unparseable", outcomes::add).decision());
            assertEquals(Decision.ALLOW, s.undecided(config("flag", "redact"), "arguments_unparseable", outcomes::add).decision());
        }

        @Test
        @DisplayName("past the model-check budget a model-backed check is undecided, a deterministic one still runs")
        void modelCap() {
            var model = new Scripted("judge", TOOL, false, t -> GuardrailVerdict.allow());
            var regex = flagging("regex", "x");
            var s = screener(model, regex);
            var result = s.screen(config("judge", "block"), "ok", CONTEXT, 0, Mode.LIVE, () -> false, outcomes::add);
            assertEquals(Decision.BLOCK, result.decision());
            assertEquals(ToolCallGuardrailScreener.REASON_MODEL_CAP, result.reason());
            assertEquals(0, model.calls.get());
            assertEquals(Decision.BLOCK, s.screen(config("regex", "block"), "x", CONTEXT, 0, Mode.LIVE, () -> false, outcomes::add).decision());
            assertEquals(1, regex.calls.get());
        }
    }

    @Nested
    @DisplayName("after approval (block-only)")
    class BlockOnly {

        @Test
        @DisplayName("a redact finding refuses the call instead of rewriting it")
        void redactBlocks() {
            var s = screener(rewriting("pii", "secret", "[X]"));
            var result = screen(s, config("pii", "redact"), "my secret", 0, Mode.BLOCK_ONLY);
            assertEquals(Decision.BLOCK, result.decision());
            assertNull(result.text());
            assertEquals(GuardrailAction.BLOCK, outcomes.get(0).action());
        }

        @Test
        @DisplayName("a reprompt finding refuses; a warn is only recorded")
        void repromptBlocksWarnAllows() {
            var s = screener(flagging("flag", "x"));
            assertEquals(Decision.BLOCK, screen(s, config("flag", "reprompt"), "x", 0, Mode.BLOCK_ONLY).decision());
            assertEquals(Decision.ALLOW, screen(s, config("flag", "warn"), "x", 0, Mode.BLOCK_ONLY).decision());
        }
    }

    @Nested
    @DisplayName("closed vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("a reason that is not a safe token is never repeated — the type stands in for it")
        void unsafeReasonReplaced() {
            var leaky = new Scripted("leaky", TOOL, true, text -> GuardrailVerdict.violation("the text said: " + text));
            var s = screener(leaky);
            var result = screen(s, config("leaky", "reprompt"), "CANARY-123", 0, Mode.LIVE);
            assertEquals("leaky", result.reason());
            assertFalse(result.message().contains("CANARY"));
            assertEquals("leaky", outcomes.get(0).reason());
        }

        @Test
        @DisplayName("issue codes that are not safe tokens are dropped")
        void unsafeIssuesDropped() {
            var s = screener(new Scripted("judge", TOOL, false,
                    text -> GuardrailVerdict.issues(List.of("OK_CODE", "not a code: " + text), "judge_issues", null)));
            assertEquals(List.of("OK_CODE"), screen(s, config("judge", "block"), "CANARY", 0, Mode.LIVE).issues());
        }
    }
}
