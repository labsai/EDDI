/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailAction;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailOutcome;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailService;
import ai.labs.eddi.modules.llm.guardrails.GuardrailUndecidedException;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.modules.llm.guardrails.PaidCallGate;
import ai.labs.eddi.modules.llm.impl.ChatModelRegistry;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.GuardrailConfig;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ClassifierGuardrail (WS4b)")
class ClassifierGuardrailTest {

    private static final String CONVERSATION = "0123456789abcdef01234567";
    private static final String AGENT = "0123456789abcdef76543210";

    private ChatModelRegistry registry;
    private SimpleMeterRegistry meterRegistry;
    private ClassifierGuardrail classifier;

    @BeforeEach
    void setUp() {
        registry = mock(ChatModelRegistry.class);
        meterRegistry = new SimpleMeterRegistry();
        classifier = new ClassifierGuardrail(registry, new TemplatingEngine(Engine.builder().addDefaults().build()), meterRegistry);
    }

    /** A classifier model that answers via {@code answer} and records requests. */
    static final class StubModel implements ChatModel {
        final List<ChatRequest> requests = new CopyOnWriteArrayList<>();
        private final Function<ChatRequest, ChatResponse> answer;

        StubModel(Function<ChatRequest, ChatResponse> answer) {
            this.answer = answer;
        }

        static StubModel answering(String text) {
            return answering(text, null);
        }

        static StubModel answering(String text, TokenUsage usage) {
            return new StubModel(r -> ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(usage).build());
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return answer.apply(request);
        }
    }

    private StubModel install(StubModel model) throws Exception {
        when(registry.getOrCreate(eq("ollama"), any())).thenReturn(model);
        return model;
    }

    private static Map<String, String> params(String... kv) {
        Map<String, String> map = new HashMap<>();
        map.put("provider", "ollama");
        map.put("model", "shieldgemma:2b");
        map.put("baseUrl", "http://classifier.guardrails.svc:11434");
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("key/value pairs expected, got " + kv.length + " values");
        }
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static GuardrailContext context(GuardrailPhase phase, Map<String, String> parameters, List<ChatMessage> messages,
                                            String retrieved, TurnDeadline deadline) {
        return new GuardrailContext(phase, "taskA", CONVERSATION, AGENT, parameters, messages, retrieved, deadline);
    }

    private static GuardrailContext input(Map<String, String> parameters) {
        return context(GuardrailPhase.INPUT, parameters, List.of(), null, null);
    }

    private static String sentText(StubModel model) {
        ChatMessage only = model.requests.get(0).messages().get(0);
        return ((UserMessage) only).singleText();
    }

    // === verdict parsing ===

    @Nested
    @DisplayName("verdict parsing")
    class Parsing {

        @ParameterizedTest(name = "[{index}] answer=''{0}'' unsafe={1} safe={2} pattern={3} -> {4}")
        @CsvSource(delimiter = '|', nullValues = "NULL", value = {
                // default labels: unsafeLabel yes, no safeLabel
                "Yes | NULL | NULL | NULL | UNSAFE",
                "yes | NULL | NULL | NULL | UNSAFE",
                "  YES. The prompt asks for... | NULL | NULL | NULL | UNSAFE",
                "**Yes** | NULL | NULL | NULL | UNSAFE",
                "No | NULL | NULL | NULL | SAFE",
                "no, it is fine | NULL | NULL | NULL | SAFE",
                // safe only on a positive match: anything else is undecided (fail closed)
                "maybe | NULL | NULL | NULL | UNPARSEABLE",
                "I cannot help with that. | NULL | NULL | NULL | UNPARSEABLE",
                "<html><body>502 Bad Gateway</body></html> | NULL | NULL | NULL | UNPARSEABLE",
                "N | NULL | NULL | NULL | UNPARSEABLE",
                // a custom unsafeLabel without safeLabel: nothing counts as safe
                "safe | unsafe | NULL | NULL | UNPARSEABLE",
                "unsafe | unsafe | NULL | NULL | UNSAFE",
                "'' | NULL | NULL | NULL | UNPARSEABLE",
                "... | NULL | NULL | NULL | UNPARSEABLE",
                // a safe label makes anything else undecided
                "No | yes | no | NULL | SAFE",
                "maybe | yes | no | NULL | UNPARSEABLE",
                "Yesterday | yes | no | NULL | UNPARSEABLE",
                // Llama-Guard-style labels on separate lines
                "unsafe\\nS2 | unsafe | safe | NULL | UNSAFE",
                "safe | unsafe | safe | NULL | SAFE",
                // a label pattern: Granite Guardian's score tag, last match wins
                "<score> yes </score> | yes | no | <score>\\s*(\\w+)\\s*</score> | UNSAFE",
                "<think>a <score> yes </score> would be wrong</think> <score> no </score> | yes | no | <score>\\s*(\\w+)\\s*</score> | SAFE",
                "no score tag here | yes | no | <score>\\s*(\\w+)\\s*</score> | UNPARSEABLE",
                "<score> yes </score> | yes | no | ([ | UNPARSEABLE"})
        void table(String answer, String unsafe, String safe, String pattern, ClassifierGuardrail.Label expected) {
            Map<String, String> p = params();
            if (unsafe != null) {
                p.put("unsafeLabel", unsafe);
            }
            if (safe != null) {
                p.put("safeLabel", safe);
            }
            if (pattern != null) {
                p.put("labelPattern", pattern);
            }
            String decoded = answer.replace("\\n", "\n");
            assertEquals(expected, ClassifierGuardrail.classify(decoded, input(p)));
        }

        @Test
        @DisplayName("labelPattern runs over the last 4 KB of the answer only")
        void labelPatternScansTheTail() {
            String pattern = "<score>\\s*(\\w+)\\s*</score>";
            Map<String, String> p = params("labelPattern", pattern, "safeLabel", "no");
            String filler = "x".repeat(100_000);
            assertEquals(ClassifierGuardrail.Label.UNSAFE, ClassifierGuardrail.classify(filler + "<score> yes </score>", input(p)));
            // a verdict outside the scanned tail is not seen: undecided, not safe
            assertEquals(ClassifierGuardrail.Label.UNPARSEABLE,
                    ClassifierGuardrail.classify("<score> no </score>" + "x".repeat(ClassifierGuardrail.MAX_LABEL_SCAN_CHARS), input(p)));
            assertEquals("yes", ClassifierGuardrail.extractLabel(filler + "<score> yes </score>", pattern));
        }

        @Test
        @DisplayName("the first word is taken from the head of a huge answer")
        void firstWordOfHugeAnswer() {
            assertEquals(ClassifierGuardrail.Label.SAFE, ClassifierGuardrail.classify("No " + "x".repeat(100_000), input(params())));
        }

        @Test
        @DisplayName("a catastrophically backtracking labelPattern is cut off and the answer is undecided")
        void pathologicalPatternIsBounded() {
            Map<String, String> p = params("labelPattern", "(a+)+$", "safeLabel", "no");
            String answer = "a".repeat(3_000) + "b";
            long start = System.nanoTime();
            assertEquals(ClassifierGuardrail.Label.UNPARSEABLE, ClassifierGuardrail.classify(answer, input(p)));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < ClassifierGuardrail.LABEL_MATCH_BUDGET_MS * 8, "took " + elapsedMs + " ms");
        }

        @Test
        @DisplayName("a null answer is undecided")
        void nullAnswer() {
            assertEquals(ClassifierGuardrail.Label.UNPARSEABLE, ClassifierGuardrail.classify(null, input(params())));
        }
    }

    // === configuration validation ===

    @Nested
    @DisplayName("validate (deploy time)")
    class Validate {

        @Test
        @DisplayName("default labels, or both labels set: valid")
        void valid() {
            assertNull(classifier.validate(params()));
            assertNull(classifier.validate(params("unsafeLabel", "YES")));
            assertNull(classifier.validate(params("unsafeLabel", "unsafe", "safeLabel", "safe")));
        }

        @Test
        @DisplayName("a custom unsafeLabel without safeLabel is refused, naming safeLabel")
        void customUnsafeWithoutSafe() {
            String problem = classifier.validate(params("unsafeLabel", "unsafe"));
            assertTrue(problem != null && problem.contains("'safeLabel'"), problem);
        }

        @Test
        @DisplayName("no provider is refused, naming provider")
        void noProvider() {
            Map<String, String> p = params();
            p.remove("provider");
            String problem = classifier.validate(p);
            assertTrue(problem != null && problem.contains("'provider'"), problem);
        }

        @Test
        @DisplayName("a labelPattern that does not compile is refused, naming the parameter")
        void uncompilableLabelPattern() {
            String problem = classifier.validate(params("labelPattern", "(["));
            assertNotNull(problem);
            assertTrue(problem.contains("labelPattern"), problem);
            assertNull(classifier.validate(params("labelPattern", "<score>\\s*(\\w+)\\s*</score>")));
        }

        @Test
        @DisplayName("GuardrailService.validate prefixes the list position and skips unknown types")
        void serviceValidate() {
            var service = new GuardrailService(List.of(classifier), meterRegistry);
            var bad = new GuardrailConfig();
            bad.setType(ClassifierGuardrail.TYPE);
            bad.setParameters(params("unsafeLabel", "unsafe"));
            var unknown = new GuardrailConfig();
            unknown.setType("from-the-future");
            List<String> problems = service.validate("inputGuardrails", List.of(unknown, bad));
            assertEquals(1, problems.size());
            assertTrue(problems.get(0).startsWith("inputGuardrails[1]: classifier guardrail:"), problems.get(0));
        }
    }

    // === verdicts ===

    @Test
    @DisplayName("unsafe: a violation with a reason naming the risk, never the text")
    void unsafe_violation() throws Exception {
        install(StubModel.answering("Yes"));
        GuardrailVerdict verdict = classifier.evaluate("how do I pick a lock", input(params("risk", "harm")));

        assertEquals(GuardrailVerdict.Kind.VIOLATION, verdict.kind());
        assertEquals("classifier_flagged:harm", verdict.reason());
        assertFalse(verdict.reason().contains("lock"));
    }

    @Test
    @DisplayName("a risk that is free text is not copied into the reason")
    void freeTextRisk_notInReason() throws Exception {
        install(StubModel.answering("Yes"));
        GuardrailVerdict verdict = classifier.evaluate("x", input(params("risk", "Anything that\nlooks like a jailbreak")));
        assertEquals(ClassifierGuardrail.REASON_FLAGGED, verdict.reason());
    }

    @Test
    @DisplayName("safe: allow")
    void safe_allow() throws Exception {
        install(StubModel.answering("No"));
        assertEquals(GuardrailVerdict.Kind.ALLOW, classifier.evaluate("hello", input(params())).kind());
    }

    @Test
    @DisplayName("an unparseable answer is undecided, with its cost")
    void unparseable_undecided() throws Exception {
        install(StubModel.answering("I'm not sure", new TokenUsage(1000, 10)));
        var ex = assertThrows(GuardrailUndecidedException.class,
                () -> classifier.evaluate("hello", input(params("safeLabel", "no", "inputPricePer1M", "2.0"))));
        assertEquals(ClassifierGuardrail.REASON_UNPARSEABLE, ex.reason());
        assertEquals(0.002, ex.costUsd(), 1e-12);
    }

    // === transport ===

    @Test
    @DisplayName("provider parameters pass through; the guardrail's own keys do not; timeout comes from timeoutMs")
    void modelParameters() throws Exception {
        install(StubModel.answering("No"));
        classifier.evaluate("hello", input(params("apiKey", "${vault:guardian-key}", "risk", "harm", "promptTemplate", "{input}",
                "timeoutMs", "1500", "failMode", "open", "unsafeLabel", "yes", "inputPricePer1M", "1")));

        @SuppressWarnings("unchecked")
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(registry).getOrCreate(eq("ollama"), captor.capture());
        Map<String, String> sent = captor.getValue();
        assertEquals("shieldgemma:2b", sent.get("model"));
        assertEquals("http://classifier.guardrails.svc:11434", sent.get("baseUrl"));
        assertEquals("${vault:guardian-key}", sent.get("apiKey"));
        assertEquals("1500", sent.get("timeout"));
        for (String own : List.of("provider", "risk", "promptTemplate", "timeoutMs", "failMode", "unsafeLabel", "inputPricePer1M")) {
            assertFalse(sent.containsKey(own), own);
        }
    }

    @Test
    @DisplayName("no promptTemplate: input is sent as the user message, output as question + assistant answer")
    void nativeConversation() throws Exception {
        StubModel model = install(StubModel.answering("No"));
        classifier.evaluate("is this ok?", input(params()));
        assertEquals("is this ok?", sentText(model));

        classifier.evaluate("the answer", context(GuardrailPhase.OUTPUT, params(),
                List.of(UserMessage.from("the question"), AiMessage.from("draft")), null, null));
        List<ChatMessage> sent = model.requests.get(1).messages();
        assertEquals(2, sent.size());
        assertEquals("the question", ((UserMessage) sent.get(0)).singleText());
        assertEquals("the answer", ((AiMessage) sent.get(1)).text());
    }

    @Test
    @DisplayName("an unsupported provider is unavailable")
    void unsupportedProvider() throws Exception {
        when(registry.getOrCreate(eq("nope"), any())).thenThrow(new ChatModelRegistry.UnsupportedLlmTaskException("Type nope"));
        var ex = assertThrows(GuardrailUndecidedException.class, () -> classifier.evaluate("x", input(params("provider", "nope"))));
        assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, ex.reason());
    }

    @Test
    @DisplayName("no provider configured is unavailable")
    void noProvider() {
        Map<String, String> p = params();
        p.remove("provider");
        var ex = assertThrows(GuardrailUndecidedException.class, () -> classifier.evaluate("x", input(p)));
        assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, ex.reason());
    }

    @Test
    @DisplayName("an unreachable classifier is unavailable, and the provider's message is not propagated")
    void unreachable() throws Exception {
        install(new StubModel(r -> {
            throw new RuntimeException("connection refused while sending 'the secret text'");
        }));
        var ex = assertThrows(GuardrailUndecidedException.class, () -> classifier.evaluate("the secret text", input(params())));
        assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, ex.reason());
        assertFalse(String.valueOf(ex.getMessage()).contains("secret"));
    }

    @Test
    @DisplayName("a classifier slower than timeoutMs is cut off and unavailable")
    void timeout() throws Exception {
        install(new StubModel(r -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("No")).build();
        }));
        long start = System.nanoTime();
        var ex = assertThrows(GuardrailUndecidedException.class, () -> classifier.evaluate("x", input(params("timeoutMs", "100"))));
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, ex.reason());
        assertTrue(tookMs < 3_000, "took " + tookMs + "ms");
        assertEquals(1L, meterRegistry.get(ClassifierGuardrail.METRIC_DURATION).tag("provider", "ollama").tag("outcome", "timeout").timer()
                .count());
    }

    @Test
    @DisplayName("the turn deadline wins when it is tighter than timeoutMs; an expired turn is not called at all")
    void turnDeadline() throws Exception {
        StubModel model = install(StubModel.answering("No"));
        long now = System.currentTimeMillis();
        TurnDeadline tight = TurnDeadline.of(Clock.systemUTC(), now, 2_000L, 1_500L);
        assertTrue(ClassifierGuardrail.budgetMs(context(GuardrailPhase.INPUT, params("timeoutMs", "3000"), List.of(), null, tight)) <= 500);

        TurnDeadline expired = TurnDeadline.of(Clock.systemUTC(), now - 10_000L, 2_000L, 0L);
        var ex = assertThrows(GuardrailUndecidedException.class,
                () -> classifier.evaluate("x", context(GuardrailPhase.INPUT, params(), List.of(), null, expired)));
        assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, ex.reason());
        assertTrue(model.requests.isEmpty());
    }

    @Test
    @DisplayName("timeoutMs: default 3000, capped, junk ignored")
    void timeoutDefaults() {
        assertEquals(3000, ClassifierGuardrail.timeoutMs(input(params())));
        assertEquals(3000, ClassifierGuardrail.timeoutMs(input(params("timeoutMs", "soon"))));
        assertEquals(3000, ClassifierGuardrail.timeoutMs(input(params("timeoutMs", "-5"))));
        assertEquals(ClassifierGuardrail.MAX_TIMEOUT_MS, ClassifierGuardrail.timeoutMs(input(params("timeoutMs", "999999"))));
    }

    // === template safety ===

    @Test
    @DisplayName("the screened text is template DATA: braces in it are never evaluated")
    void templateInjection() throws Exception {
        StubModel model = install(StubModel.answering("No"));
        String hostile = "{risk} {#for i in input}X{/for} {inject:foo} {input}";
        classifier.evaluate(hostile, input(params("risk", "jailbreak", "promptTemplate", "Risk: {risk}\nUser: {input}\nAnswer yes or no.")));

        assertEquals("Risk: jailbreak\nUser: " + hostile + "\nAnswer yes or no.", sentText(model));
    }

    @Test
    @DisplayName("output phase: {output}, {input} and — with includeRetrievedContext — {context}")
    void outputTemplateWithContext() throws Exception {
        StubModel model = install(StubModel.answering("No"));
        String template = "Q: {input}\nA: {output}\nDocs: {context}";
        List<ChatMessage> transcript = List.of(UserMessage.from("When does the shop open?"));

        classifier.evaluate("At 9.", context(GuardrailPhase.OUTPUT, params("promptTemplate", template, "includeRetrievedContext", "true"),
                transcript, "Opening hours: 9-17 {not a template}", null));
        assertEquals("Q: When does the shop open?\nA: At 9.\nDocs: Opening hours: 9-17 {not a template}", sentText(model));

        classifier.evaluate("At 9.", context(GuardrailPhase.OUTPUT, params("promptTemplate", template), transcript, "Opening hours", null));
        assertEquals("Q: When does the shop open?\nA: At 9.\nDocs: ", ((UserMessage) model.requests.get(1).messages().get(0)).singleText());
    }

    @Test
    @DisplayName("a template that does not render is unavailable")
    void brokenTemplate() throws Exception {
        install(StubModel.answering("No"));
        var ex = assertThrows(GuardrailUndecidedException.class,
                () -> classifier.evaluate("x", input(params("promptTemplate", "{#if input}unclosed"))));
        assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, ex.reason());
    }

    // === cost ===

    @Test
    @DisplayName("cost: priced from token usage with inputPricePer1M / outputPricePer1M")
    void cost() throws Exception {
        install(StubModel.answering("No", new TokenUsage(2_000, 1)));
        GuardrailVerdict verdict = classifier.evaluate("x", input(params("inputPricePer1M", "0.5", "outputPricePer1M", "10")));
        assertEquals(2_000 / 1e6 * 0.5 + 1 / 1e6 * 10, verdict.costUsd(), 1e-12);

        install(StubModel.answering("Yes", new TokenUsage(2_000, 1)));
        assertEquals(0.0, classifier.evaluate("x", input(params())).costUsd());
    }

    @Test
    @DisplayName("history is not screened by the classifier")
    void noHistory() {
        assertFalse(classifier.screensHistory());
        assertEquals(Set.of(GuardrailPhase.INPUT, GuardrailPhase.OUTPUT), classifier.phases());
    }

    // === through the GuardrailService: failMode ===

    @Nested
    @DisplayName("failMode through GuardrailService")
    class FailMode {

        private final List<GuardrailOutcome> outcomes = new ArrayList<>();
        private GuardrailService service;

        @BeforeEach
        void service() throws Exception {
            service = new GuardrailService(List.of(classifier), meterRegistry);
            install(new StubModel(r -> {
                throw new RuntimeException("connection refused");
            }));
        }

        private GuardrailConfig config(String action, String failMode) {
            var cfg = new GuardrailConfig();
            cfg.setType(ClassifierGuardrail.TYPE);
            cfg.setAction(action);
            cfg.setBlockMessage("Blocked.");
            Map<String, String> p = params();
            if (failMode != null) {
                p.put("failMode", failMode);
            }
            cfg.setParameters(p);
            return cfg;
        }

        @Test
        @DisplayName("unavailable + block, failMode unset (closed): blocked with classifier_unavailable")
        void closedByDefault() {
            var result = service.screenInput(List.of(config("block", null)), "hello", input(Map.of()), outcomes::add);
            assertTrue(result.isBlocked());
            assertEquals("Blocked.", result.blockMessage());
            assertEquals(GuardrailAction.BLOCK, outcomes.get(0).action());
            assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, outcomes.get(0).reason());
        }

        @Test
        @DisplayName("unavailable + failMode open: a warning with classifier_unavailable, the text passes")
        void open() {
            var result = service.screenInput(List.of(config("block", "open")), "hello", input(Map.of()), outcomes::add);
            assertFalse(result.isBlocked());
            assertEquals("hello", result.text());
            assertEquals(GuardrailAction.WARN, outcomes.get(0).action());
            assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, outcomes.get(0).reason());
        }

        @Test
        @DisplayName("output: unavailable + failMode closed withholds the answer")
        void outputClosed() throws Exception {
            var result = service.runOutput(List.of(config("block", "closed")), "an answer", null,
                    context(GuardrailPhase.OUTPUT, Map.of(), List.of(), null, null), outcomes::add);
            assertTrue(result.isBlocked());
            assertEquals("Blocked.", result.text());
            assertEquals(ClassifierGuardrail.REASON_UNAVAILABLE, outcomes.get(0).reason());
        }

        @Test
        @DisplayName("an unparseable answer's cost still counts toward the turn")
        void unparseableCostCounts() throws Exception {
            install(StubModel.answering("hmm", new TokenUsage(1_000_000, 0)));
            var cfg = config("block", "open");
            cfg.getParameters().put("safeLabel", "no");
            cfg.getParameters().put("inputPricePer1M", "3");
            var result = service.screenInput(List.of(cfg), "hello", input(Map.of()), outcomes::add);
            assertFalse(result.isBlocked());
            assertEquals(3.0, result.costUsd(), 1e-9);
            assertEquals(ClassifierGuardrail.REASON_UNPARSEABLE, outcomes.get(0).reason());
        }

        @Test
        @DisplayName("a guardrail that throws something else is still guardrail_error")
        void genericError() {
            var broken = new ContentGuardrail() {
                @Override
                public String type() {
                    return "broken";
                }

                @Override
                public Set<GuardrailPhase> phases() {
                    return Set.of(GuardrailPhase.INPUT);
                }

                @Override
                public GuardrailVerdict evaluate(String text, GuardrailContext context) {
                    throw new IllegalStateException("bug");
                }
            };
            var svc = new GuardrailService(List.of(broken), meterRegistry);
            var cfg = new GuardrailConfig();
            cfg.setType("broken");
            var result = svc.screenInput(List.of(cfg), "hello", input(Map.of()), outcomes::add);
            assertTrue(result.isBlocked());
            assertEquals(GuardrailService.REASON_ERROR, outcomes.get(0).reason());
        }
    }

    // === spend checks (E4) ===

    @Nested
    @DisplayName("spend checks: the classifier's call is admitted before it starts")
    class Spend {

        private final List<Double> expected = new ArrayList<>();
        private final List<Double> spent = new ArrayList<>();
        private int open;

        private PaidCallGate gate(boolean admit) {
            return expectedUsd -> {
                expected.add(expectedUsd);
                open++;
                return new PaidCallGate.PaidCall() {
                    @Override
                    public String refusalReason() {
                        return admit ? null : "conversation_ceiling";
                    }

                    @Override
                    public void spent(double actualUsd) {
                        spent.add(actualUsd);
                    }

                    @Override
                    public void close() {
                        open--;
                    }
                };
            };
        }

        @Test
        @DisplayName("refused: the model is never called, and the guardrail could not decide with reason spend_refused")
        void refusedIsUndecided() throws Exception {
            var model = install(StubModel.answering("no"));

            var undecided = assertThrows(GuardrailUndecidedException.class,
                    () -> classifier.evaluate("hello", input(params()).withSpend(gate(false))));

            assertEquals(PaidCallGate.REASON_SPEND_REFUSED, undecided.reason());
            assertEquals(0.0, undecided.costUsd());
            assertTrue(model.requests.isEmpty());
            assertEquals(0, open);
        }

        @Test
        @DisplayName("refused through the service: failMode closed blocks with spend_refused, open lets the text pass with a warning")
        void failModeApplies() throws Exception {
            install(StubModel.answering("no"));
            var service = new GuardrailService(List.of(classifier), meterRegistry);
            List<GuardrailOutcome> outcomes = new ArrayList<>();
            var closed = new GuardrailConfig();
            closed.setType(ClassifierGuardrail.TYPE);
            closed.setAction("block");
            closed.setParameters(params());
            var open = new GuardrailConfig();
            open.setType(ClassifierGuardrail.TYPE);
            open.setAction("block");
            open.setParameters(params("failMode", "open"));

            var blocked = service.screenInput(List.of(closed), "hello", input(Map.of()).withSpend(gate(false)), outcomes::add);
            var passed = service.screenInput(List.of(open), "hello", input(Map.of()).withSpend(gate(false)), outcomes::add);

            assertTrue(blocked.isBlocked());
            assertFalse(passed.isBlocked());
            assertEquals(PaidCallGate.REASON_SPEND_REFUSED, outcomes.get(0).reason());
            assertEquals(GuardrailAction.WARN, outcomes.get(1).action());
            assertEquals(PaidCallGate.REASON_SPEND_REFUSED, outcomes.get(1).reason());
        }

        @Test
        @DisplayName("admitted: the call runs, its cost is reported, the expected cost comes from the prompt and the input price")
        void admittedReportsCost() throws Exception {
            install(StubModel.answering("no", new TokenUsage(1_000_000, 0)));

            var verdict = classifier.evaluate("x".repeat(400), input(params("inputPricePer1M", "2")).withSpend(gate(true)));

            assertFalse(verdict.triggered());
            assertEquals(List.of(2.0), spent);
            // 400 characters ~ 100 tokens at $2 per 1M
            assertEquals(100 * 2.0 / 1_000_000, expected.get(0), 1e-12);
            assertEquals(0, open);
        }

        @Test
        @DisplayName("an unparseable answer still reports its cost; a failed call releases its admission")
        void unparseableAndFailure() throws Exception {
            install(StubModel.answering("hmm", new TokenUsage(1_000_000, 0)));
            assertThrows(GuardrailUndecidedException.class,
                    () -> classifier.evaluate("hello", input(params("inputPricePer1M", "3")).withSpend(gate(true))));
            assertEquals(List.of(3.0), spent);

            install(new StubModel(r -> {
                throw new RuntimeException("connection refused");
            }));
            assertThrows(GuardrailUndecidedException.class, () -> classifier.evaluate("hello", input(params()).withSpend(gate(true))));
            assertEquals(0, open);
        }

        @Test
        @DisplayName("an unpriced classifier expects nothing")
        void unpricedExpectsNothing() {
            assertEquals(0.0, GuardrailModelClient.expectedCost(List.of(UserMessage.from("hello")), input(params())));
        }
    }
}
