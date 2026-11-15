/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.context;

import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.Settings;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ApprovalContextRenderer")
class ApprovalContextRendererTest {

    private SimpleMeterRegistry registry;
    private Map<String, Object> converted;
    private ConversationMemory memory;
    private ApprovalContextRenderer renderer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        converted = new LinkedHashMap<>();
        memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        renderer = rendererWith(8_000);
    }

    private ApprovalContextRenderer rendererWith(int maxTotal) {
        ITemplatingEngine engine = new TemplatingEngine(Engine.builder().addDefaults().build(),
                new Settings(16_000, 2_000, false, true, Settings.DEFAULT_ITERATION_METADATA_PREFIX, 500));
        IMemoryItemConverter converter = m -> new LinkedHashMap<>(converted);
        return new ApprovalContextRenderer(engine, converter, registry, maxTotal);
    }

    private static ApprovalContextBlock block(String label, String template, Integer maxChars) {
        return new ApprovalContextBlock(label, template, maxChars);
    }

    private double count(String outcome) {
        var counter = registry.find(ApprovalContextRenderer.METRIC).tag("outcome", outcome).tag("surface", "regular").counter();
        return counter == null ? 0 : counter.count();
    }

    private static PendingToolCall call(String id, String tool, String redactedArgs, String matchedRule) {
        var call = new PendingToolCall();
        call.setCallId(id);
        call.setToolName(tool);
        call.setSource("mcp");
        call.setGateReason("mcp:*");
        call.setArgumentsRedacted(redactedArgs);
        call.setMatchedRule(matchedRule);
        return call;
    }

    private static ToolApprovalsConfig.ApprovalRule rule(String match, ApprovalContextBlock... blocks) {
        var rule = new ToolApprovalsConfig.ApprovalRule();
        rule.setMatch(match);
        rule.setContext(List.of(blocks));
        return rule;
    }

    private void pauseWith(ToolApprovalsConfig config, PendingToolCall... calls) {
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(calls));
        batch.setEffectiveToolApprovals(config);
        memory.setHitlPauseType("TOOL_CALL");
        memory.setHitlPendingToolCalls(batch);
        memory.setHitlPauseReason("Publishing to LinkedIn");
    }

    // ------------------------------------------------------------------ basics

    @Test
    @DisplayName("no config means null: nothing rendered, nothing stored")
    void noConfigIsNull() {
        assertNull(renderer.renderForConversation(memory, null, null));
        assertNull(renderer.renderForConversation(memory, List.of(), null));
    }

    @Test
    @DisplayName("renders the author's template over conversation data and the hitl root")
    void rendersTemplatesOverData() {
        converted.put("properties", Map.of("gtm", Map.of("draft", "Acme cuts onboarding to 3 days")));
        memory.setHitlPauseReason("Needs sign-off");
        var ctx = renderer.renderForConversation(memory,
                List.of(block("Draft", "{properties.gtm.draft}", null), block("Why", "{hitl.reason} ({hitl.pauseType})", null)), null);

        assertEquals(2, ctx.getItems().size());
        assertEquals("Draft", ctx.getItems().get(0).getLabel());
        assertEquals("Acme cuts onboarding to 3 days", ctx.getItems().get(0).getText());
        assertEquals("agent", ctx.getItems().get(0).getSource());
        assertEquals("Needs sign-off (RULE)", ctx.getItems().get(1).getText());
        assertNotNull(ctx.getDigest());
        assertEquals(2, count("ok"));
    }

    @Test
    @DisplayName("loops over structured data: sources and claims")
    void loopsOverStructuredData() {
        converted.put("properties", Map.of("gtm", Map.of("sources",
                List.of(Map.of("title", "Case study", "url", "https://a.example"), Map.of("title", "Export", "url", "https://b.example")))));
        var ctx = renderer.renderForConversation(memory,
                List.of(block("Sources", "{#for s in properties.gtm.sources}{s_count}. {s.title} — {s.url}\n{/for}", null)), null);

        assertEquals("1. Case study — https://a.example\n2. Export — https://b.example", ctx.getItems().get(0).getText());
    }

    @Test
    @DisplayName("a block whose render is blank is dropped (conditional blocks)")
    void blankBlockDropped() {
        converted.put("properties", Map.of("x", "value"));
        var ctx = renderer.renderForConversation(memory,
                List.of(block("Empty", "{properties.missing}", null), block("Cond", "{#if properties.x == 'nope'}shown{/if}", null),
                        block("Kept", "{properties.x}", null)),
                null);

        assertEquals(1, ctx.getItems().size());
        assertEquals("Kept", ctx.getItems().get(0).getLabel());
        assertEquals(2, count("empty"));
    }

    @Test
    @DisplayName("everything blank yields null, not an empty context")
    void allBlankIsNull() {
        assertNull(renderer.renderForConversation(memory, List.of(block("Empty", "{properties.missing}", null)), null));
    }

    // ------------------------------------------------------------------ bounds

    @Test
    @DisplayName("per-block cut: exactly maxChars is kept, one more is cut with an ellipsis within the limit")
    void perBlockBoundary() {
        converted.put("properties", Map.of("t50", "a".repeat(50), "t51", "a".repeat(51)));
        var ctx = renderer.renderForConversation(memory,
                List.of(block("exact", "{properties.t50}", 50), block("over", "{properties.t51}", 50)), null);

        Item exact = ctx.getItems().get(0);
        assertEquals(50, exact.getText().length());
        assertFalse(exact.isTruncated(), "length == maxChars is not truncated");
        Item over = ctx.getItems().get(1);
        assertTrue(over.isTruncated());
        assertTrue(over.getText().endsWith("…"));
        assertTrue(over.getText().length() <= 50, "the ellipsis counts inside the limit");
    }

    @Test
    @DisplayName("the cut never splits a surrogate pair")
    void cutIsCodePointSafe() {
        // limit 50 -> 49 chars + ellipsis; char 48 is a high surrogate
        converted.put("properties", Map.of("t", "a".repeat(48) + "😀" + "b".repeat(30)));
        var ctx = renderer.renderForConversation(memory, List.of(block("emoji", "{properties.t}", 50)), null);

        String text = ctx.getItems().get(0).getText();
        assertEquals("a".repeat(48) + "…", text);
        assertEquals(text, new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), "round-trips: no lone surrogate");
    }

    @Test
    @DisplayName("the total cap stops rendering and sets truncated")
    void totalCapStops() {
        var small = rendererWith(120);
        converted.put("properties", Map.of("t", "x".repeat(100)));
        var ctx = small.renderForConversation(memory, List.of(block("one", "{properties.t}", 100), block("two", "{properties.t}", 100),
                block("three", "{properties.t}", 100)), null);

        assertTrue(ctx.isTruncated());
        assertEquals(2, ctx.getItems().size(), "the third block does not fit at all");
        assertEquals(100, ctx.getItems().get(0).getText().length());
        assertEquals(20, ctx.getItems().get(1).getText().length(), "the second is cut to what is left of the total");
        assertTrue(ctx.getItems().get(1).isTruncated());
    }

    @Test
    @DisplayName("a total cap that is exactly filled does not claim truncation")
    void totalCapBoundary() {
        var small = rendererWith(100);
        converted.put("properties", Map.of("t", "x".repeat(100)));
        var ctx = small.renderForConversation(memory, List.of(block("one", "{properties.t}", 100)), null);

        assertFalse(ctx.isTruncated());
        assertEquals(100, ctx.getItems().get(0).getText().length());
        assertFalse(ctx.getItems().get(0).isTruncated());
    }

    // ------------------------------------------------------------------ secrets

    @Test
    @DisplayName("reference tokens are masked and secret shapes are redacted")
    void secretsNeverReachTheCard() {
        String apiKey = "sk-ant-" + "fixture".repeat(5);
        converted.put("properties", Map.of("ref", "token ${vault:openai-key} and ${caller:authorization}", "key", "the key " + apiKey + " is here",
                "conn", "use ${connection:github} please"));
        var ctx = renderer.renderForConversation(memory,
                List.of(block("refs", "{properties.ref}", null), block("key", "{properties.key}", null), block("conn", "{properties.conn}", null)),
                null);

        String all = ctx.getItems().stream().map(Item::getText).reduce("", (a, b) -> a + "|" + b);
        assertFalse(all.contains("openai-key"), all);
        assertFalse(all.contains("authorization"), all);
        assertFalse(all.contains("github"), all);
        assertFalse(all.contains(apiKey), all);
        assertTrue(all.contains("[secret]"));
    }

    @Test
    @DisplayName("tool arguments come from the REDACTED text; the raw arguments are never read")
    void rawArgumentsAreNeverRead() {
        PendingToolCall call = spy(call("c1", "publish_linkedin_post", "{\"text\":\"hello world\",\"visibility\":\"PUBLIC\"}", "mcp:publish_*"));
        call.setArgumentsRaw("{\"text\":\"hello world\",\"apiKey\":\"RAW-ONLY-VALUE\"}");
        var cfg = new ToolApprovalsConfig();
        cfg.setRules(List.of(rule("mcp:publish_*", block("Post", "{call.args.text} / {call.args.visibility} / {call.arguments}", null))));
        pauseWith(cfg, call);

        var ctx = renderer.renderForConversation(memory, null, null);

        verify(call, never()).getArgumentsRaw();
        String text = ctx.getItems().get(0).getText();
        assertTrue(text.startsWith("hello world / PUBLIC / "), text);
        assertFalse(text.contains("RAW-ONLY-VALUE"));
    }

    @Test
    @DisplayName("redaction is applied again to arguments that were stored before a filter improvement")
    void argumentsAreReRedacted() {
        String apiKey = "sk-ant-" + "fixture".repeat(5);
        var cfg = new ToolApprovalsConfig();
        cfg.setRules(List.of(rule("mcp:publish_*", block("Args", "{call.arguments}", null))));
        pauseWith(cfg, call("c1", "publish", "{\"note\":\"" + apiKey + "\"}", "mcp:publish_*"));

        var ctx = renderer.renderForConversation(memory, null, null);

        assertFalse(ctx.getItems().get(0).getText().contains(apiKey));
    }

    // ------------------------------------------------------------------ rules and
    // calls

    @Test
    @DisplayName("rule blocks bind to the call their rule matched, one item per call")
    void ruleBlocksBindPerCall() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRules(List.of(rule("mcp:publish_linkedin_post", block("Post", "{call.args.text}", null)),
                rule("mcp:publish_blog", block("Blog", "{call.args.title}", null))));
        pauseWith(cfg, call("c1", "publish_linkedin_post", "{\"text\":\"short post\"}", "mcp:publish_linkedin_post"),
                call("c2", "publish_blog", "{\"title\":\"Big news\"}", "mcp:publish_blog"),
                call("c3", "other", "{\"text\":\"not matched\"}", null));

        var ctx = renderer.renderForConversation(memory, null, null);

        assertEquals(2, ctx.getItems().size());
        Item first = ctx.getItems().get(0);
        assertEquals("short post", first.getText());
        assertEquals("rule:mcp:publish_linkedin_post", first.getSource());
        assertEquals("c1", first.getCallId());
        assertEquals("Post — publish_linkedin_post", first.getLabel(), "several calls: the tool name tells the items apart");
        Item second = ctx.getItems().get(1);
        assertEquals("Big news", second.getText());
        assertEquals("c2", second.getCallId());
    }

    @Test
    @DisplayName("a single gated call keeps the plain label")
    void singleCallKeepsLabel() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRules(List.of(rule("mcp:publish_linkedin_post", block("Post text", "{call.args.text}", null))));
        pauseWith(cfg, call("c1", "publish_linkedin_post", "{\"text\":\"hi\"}", "mcp:publish_linkedin_post"));

        assertEquals("Post text", renderer.renderForConversation(memory, null, null).getItems().get(0).getLabel());
    }

    @Test
    @DisplayName("agent blocks come before rule blocks; the fallback toolApprovals is used when the batch carries none")
    void agentThenRule() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRules(List.of(rule("mcp:x", block("R", "rule-{call.name}", null))));
        pauseWith(null, call("c1", "x", "{}", "mcp:x"));
        converted.put("properties", Map.of("a", "agent-text"));

        var ctx = renderer.renderForConversation(memory, List.of(block("A", "{properties.a}", null)), cfg);

        assertEquals(List.of("agent-text", "rule-x"), ctx.getItems().stream().map(Item::getText).toList());
    }

    @Test
    @DisplayName("hitl.toolCalls exposes name, source, gateReason and the executed ungated calls")
    void hitlRootShape() {
        pauseWith(null, call("c1", "publish", "{\"a\":1}", null));
        memory.getHitlPendingToolCalls().setExecutedUngatedCallNames(List.of("search", "read"));

        var ctx = renderer.renderForConversation(memory, List.of(block("H",
                "{#for c in hitl.toolCalls}{c.name}|{c.source}|{c.gateReason}{/for} ungated={#for n in hitl.executedUngatedCalls}{n},{/for}", null)),
                null);

        assertEquals("publish|mcp|mcp:* ungated=search,read,", ctx.getItems().get(0).getText());
    }

    // ------------------------------------------------------------------ failure
    // isolation

    @Test
    @DisplayName("a template that throws becomes an item with an error; the others still render and nothing is thrown")
    void renderFailureIsIsolated() throws Exception {
        ITemplatingEngine broken = mock(ITemplatingEngine.class);
        when(broken.processTemplate(any(), any(), any())).thenThrow(new ITemplatingEngine.TemplateEngineException("boom", new RuntimeException()))
                .thenReturn("fine");
        var r = new ApprovalContextRenderer(broken, m -> new LinkedHashMap<>(), registry, 8_000);

        RenderedApprovalContext ctx = assertDoesNotThrow(
                () -> r.renderForConversation(memory, List.of(block("bad", "{x}", null), block("good", "{y}", null)), null));

        assertEquals(2, ctx.getItems().size());
        assertEquals("render_failed", ctx.getItems().get(0).getError());
        assertEquals("", ctx.getItems().get(0).getText());
        assertNull(ctx.getItems().get(1).getError());
        assertEquals("fine", ctx.getItems().get(1).getText());
        assertEquals(1, count("failed"));
    }

    @Test
    @DisplayName("an unexpected runtime failure in the engine is contained too")
    void runtimeFailureIsContained() throws Exception {
        ITemplatingEngine broken = mock(ITemplatingEngine.class);
        when(broken.processTemplate(any(), any(), any())).thenThrow(new IllegalStateException("kaput"));
        var r = new ApprovalContextRenderer(broken, m -> new LinkedHashMap<>(), registry, 8_000);

        RenderedApprovalContext ctx = assertDoesNotThrow(() -> r.renderForConversation(memory, List.of(block("bad", "{x}", null)), null));
        assertEquals("render_failed", ctx.getItems().get(0).getError());
    }

    @Test
    @DisplayName("a converter failure yields no context and no exception")
    void converterFailureIsContained() {
        var r = new ApprovalContextRenderer(mock(ITemplatingEngine.class), m -> {
            throw new IllegalStateException("no memory");
        }, registry, 8_000);

        assertNull(assertDoesNotThrow(() -> r.renderForConversation(memory, List.of(block("a", "{x}", null)), null)));
        assertEquals(1, count("failed"));
    }

    @Test
    @DisplayName("a runaway loop hits the iteration limit and is reported as too_large")
    void runawayLoopIsTooLarge() {
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            many.add(i);
        }
        converted.put("properties", Map.of("many", many));

        var ctx = renderer.renderForConversation(memory, List.of(block("loop", "{#for n in properties.many}{n}{/for}", null)), null);

        assertEquals("too_large", ctx.getItems().get(0).getError());
        assertEquals(1, count("too_large"));
    }

    @Test
    @DisplayName("an oversized render is reported as too_large")
    void oversizedRenderIsTooLarge() {
        converted.put("properties", Map.of("big", "z".repeat(40_000)));

        var ctx = renderer.renderForConversation(memory, List.of(block("big", "{properties.big}", null)), null);

        assertEquals("too_large", ctx.getItems().get(0).getError());
    }

    // ------------------------------------------------------------------ misc

    @Test
    @DisplayName("appendItems merges guardrail-style items and recomputes the digest")
    void appendItemsMerges() {
        converted.put("properties", Map.of("a", "one"));
        memory.setHitlApprovalContext(renderer.renderForConversation(memory, List.of(block("A", "{properties.a}", null)), null));
        String before = memory.getHitlApprovalContext().getDigest();

        renderer.appendItems(memory, List.of(new Item("Guardrail", "argument looks like an email", false, "guardrail", "c1", null)));

        assertEquals(2, memory.getHitlApprovalContext().getItems().size());
        assertEquals("guardrail", memory.getHitlApprovalContext().getItems().get(1).getSource());
        assertNotEquals(before, memory.getHitlApprovalContext().getDigest());
    }

    @Test
    @DisplayName("appendItems on an empty memory creates the context")
    void appendItemsCreates() {
        renderer.appendItems(memory, List.of(new Item("G", "t", false, "guardrail", null, null)));
        assertEquals(1, memory.getHitlApprovalContext().getItems().size());
        renderer.appendItems(memory, List.of());
        assertEquals(1, memory.getHitlApprovalContext().getItems().size());
    }

    @Test
    @DisplayName("the digest depends on the text shown")
    void digestTracksText() {
        var a = RenderedApprovalContext.of(List.of(new Item("L", "one", false, "agent", null, null)), false);
        var b = RenderedApprovalContext.of(List.of(new Item("L", "two", false, "agent", null, null)), false);
        var c = RenderedApprovalContext.of(List.of(new Item("L", "one", false, "agent", null, null)), false);
        assertNotEquals(a.getDigest(), b.getDigest());
        assertEquals(a.getDigest(), c.getDigest());
    }

    @Test
    @DisplayName("a block that renders per call never exposes more than the first five calls")
    void callCountIsBounded() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRules(List.of(rule("mcp:x", block("R", "{call.name}", null))));
        PendingToolCall[] calls = new PendingToolCall[8];
        for (int i = 0; i < calls.length; i++) {
            calls[i] = call("c" + i, "x", "{}", "mcp:x");
        }
        pauseWith(cfg, calls);

        assertEquals(ApprovalContextTemplateData.MAX_CALLS, renderer.renderForConversation(memory, null, null).getItems().size());
    }
}
