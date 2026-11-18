/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.tools.ApprovedCallScope;
import ai.labs.eddi.engine.hitl.tools.ApproverRule;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.hitl.tools.MandatoryApproval;
import ai.labs.eddi.engine.hitl.tools.MandatoryApprovalAttribution;
import ai.labs.eddi.engine.hitl.tools.MandatoryApprovalAttribution.ActingAs;
import ai.labs.eddi.engine.hitl.tools.MandatoryApprovalRule;
import ai.labs.eddi.engine.hitl.tools.MandatoryApprovals;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalGate;
import ai.labs.eddi.engine.hitl.tools.ToolApprovalRequiredException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemorySnapshotService;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolExecutionService;
import ai.labs.eddi.modules.llm.tools.ToolInvocation;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tool loop with mandatory, argument-aware approvals (WP-0.2): assembly
 * collects the rules from the tool sources, the live loop gates and pauses — or
 * refuses where it cannot pause — and the resumed loop executes a mandatory
 * call only on a checked approval, with who approved it bound around the call.
 */
@DisplayName("Tool loop — mandatory, argument-aware approvals")
class ToolLoopMandatoryApprovalTest {

    private static final String USER_ID = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String ADMIN_ID = "64b7f0c2a1d3e4f5a6b7c8e1";

    /**
     * Any calculation that mentions "file" needs the conversation user's consent.
     */
    private static final MandatoryApprovalRule CONSENT = request -> request.arguments() != null && request.arguments().contains("file")
            ? Optional.of(new MandatoryApproval("file-egress", ApproverRule.CONVERSATION_USER, "Sends 1 file out of this deployment"))
            : Optional.empty();

    @Mock
    private CalculatorTool calculatorTool;
    @Mock
    private DateTimeTool dateTimeTool;
    @Mock
    private WebSearchTool webSearchTool;
    @Mock
    private DataFormatterTool dataFormatterTool;
    @Mock
    private WebScraperTool webScraperTool;
    @Mock
    private TextSummarizerTool textSummarizerTool;
    @Mock
    private PdfReaderTool pdfReaderTool;
    @Mock
    private WeatherTool weatherTool;
    @Mock
    private FetchToolResponsePageTool fetchToolResponsePageTool;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private McpToolProviderManager mcpToolProviderManager;
    @Mock
    private A2AToolProviderManager a2aToolProviderManager;
    @Mock
    private IWorkflowStore restWorkflowStore;
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IApiCallExecutor apiCallExecutor;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private ToolResponseTruncator toolResponseTruncator;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private MemorySnapshotService memorySnapshotService;
    @Mock
    private IHitlToolJournalStore journalStore;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        orchestrator = new AgentOrchestrator(calculatorTool, dateTimeTool, webSearchTool, dataFormatterTool, webScraperTool, textSummarizerTool,
                pdfReaderTool, weatherTool, fetchToolResponsePageTool, toolExecutionService, mcpToolProviderManager, a2aToolProviderManager,
                restWorkflowStore, resourceClientLibrary, apiCallExecutor, jsonSerialization, memoryItemConverter, userMemoryStore,
                toolResponseTruncator, tenantQuotaService, memorySnapshotService, null, null, null, null, null, journalStore,
                new ConversationHistoryBuilder(), new TokenCounterFactory());
        // The rules arrive the way a real source supplies them: through the provider
        // SPI.
        var rulesProvider = mock(AgentOpsToolsProvider.class);
        when(rulesProvider.source()).thenReturn("builtin");
        when(rulesProvider.contribute(any())).thenReturn(ToolContribution.empty());
        when(rulesProvider.mandatoryApprovals(any())).thenReturn(List.of(CONSENT));
        orchestrator.agentOpsToolsProvider = rulesProvider;

        when(memory.getConversationId()).thenReturn("64b7f0c2a1d3e4f5a6b7c8a1");
        when(memory.getUserId()).thenReturn(USER_ID);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(toolResponseTruncator.truncateIfNeeded(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(tenantQuotaService.getDefaultTenantId()).thenReturn("t");
        when(tenantQuotaService.checkCostBudget(any(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        when(toolExecutionService.executeToolWrapped(any(ToolInvocation.class), anyString(), nullable(String.class), any(), any(Supplier.class),
                anyBoolean(), anyBoolean(), anyBoolean(), anyInt(), anyInt())).thenAnswer(inv -> {
                    Supplier<String> sup = inv.getArgument(4);
                    return sup.get();
                });
    }

    private static LlmConfiguration.Task calculatorTask() {
        var task = new LlmConfiguration.Task();
        task.setId("llmTask-A");
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of("calculator", "datetime"));
        task.setEnableHttpCallTools(false);
        task.setEnableMcpCallTools(false);
        return task;
    }

    private static ChatResponse toolBatch(ToolExecutionRequest... requests) {
        return ChatResponse.builder().aiMessage(AiMessage.from(List.of(requests))).build();
    }

    private static ChatResponse text(String s) {
        return ChatResponse.builder().aiMessage(AiMessage.from(s)).build();
    }

    private static ToolExecutionRequest fileCalc(String id) {
        return ToolExecutionRequest.builder().id(id).name("calculate").arguments("{\"expression\":\"file\"}").build();
    }

    private static ToolApprovalsConfig exemptAll() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("*"));
        cfg.setExempt(List.of("calculate", "getCurrentDateTime"));
        return cfg;
    }

    /** The NOT_EXECUTED results the loop handed back to the model. */
    private static List<String> resultsSentToModel(ChatModel chatModel) {
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, atLeastOnce()).chat(captor.capture());
        List<String> results = new ArrayList<>();
        for (ChatRequest request : captor.getAllValues()) {
            for (ChatMessage message : request.messages()) {
                if (message instanceof ToolExecutionResultMessage result) {
                    results.add(result.text());
                }
            }
        }
        return results;
    }

    @Nested
    @DisplayName("live loop")
    class Live {

        @Test
        @DisplayName("exempt tool + matching arguments: the turn pauses, the tool never runs, the batch carries the marker")
        void exemptStillPauses() {
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(fileCalc("c1")));

            var pause = assertThrows(ToolApprovalRequiredException.class, () -> orchestrator.executeIfToolsEnabled(chatModel, "sys",
                    List.of(UserMessage.from("hi")), calculatorTask(), memory, exemptAll(), 0));

            verify(calculatorTool, never()).calculate(anyString());
            var call = pause.getBatch().getCalls().get(0);
            assertEquals("file-egress", call.getMandatoryReasonCode());
            assertEquals("CONVERSATION_USER", call.getMandatoryApprover());
            assertEquals("Sends 1 file out of this deployment", call.getMandatorySummary());
            assertEquals(MandatoryApprovals.GATE_REASON_PREFIX + "file-egress", call.getGateReason());
        }

        @Test
        @DisplayName("no approval config at all and a call without an id: the turn pauses on a minted id")
        void inertGateMintsIds() {
            ChatModel chatModel = mock(ChatModel.class);
            var noId = ToolExecutionRequest.builder().name("calculate").arguments("{\"expression\":\"file\"}").build();
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(noId));

            var pause = assertThrows(ToolApprovalRequiredException.class, () -> orchestrator.executeIfToolsEnabled(chatModel, "sys",
                    List.of(UserMessage.from("hi")), calculatorTask(), memory, null, 0));

            var call = pause.getBatch().getCalls().get(0);
            assertTrue(call.getCallId().startsWith("gen-"), call.getCallId());
            assertTrue(call.isMandatory());
            verify(calculatorTool, never()).calculate(anyString());
        }

        @Test
        @DisplayName("a call the rules leave alone runs as before")
        void plainCallRuns() throws Exception {
            ChatModel chatModel = mock(ChatModel.class);
            var plain = ToolExecutionRequest.builder().id("c1").name("calculate").arguments("{\"expression\":\"6*7\"}").build();
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(plain), text("42"));
            when(calculatorTool.calculate("6*7")).thenReturn("42");

            var result = orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), calculatorTask(), memory,
                    exemptAll(), 0);

            assertEquals("42", result.response());
            verify(calculatorTool, times(1)).calculate("6*7");
        }

        @Test
        @DisplayName("no conversation user: refused NOT_EXECUTED, no pause, the tool never runs")
        void noUserRefused() throws Exception {
            when(memory.getUserId()).thenReturn(null);
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(fileCalc("c1")), text("could not send"));

            orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), calculatorTask(), memory, exemptAll(), 0);

            verify(calculatorTool, never()).calculate(anyString());
            assertTrue(resultsSentToModel(chatModel).stream().anyMatch(r -> r.contains(MandatoryApprovals.UNPAUSABLE_NO_USER)));
        }

        @Test
        @DisplayName("a group member's turn: refused NOT_EXECUTED, no pause, the tool never runs")
        @SuppressWarnings("unchecked")
        void groupTurnRefused() throws Exception {
            IData<Object> groupContext = mock(IData.class);
            when(groupContext.getResult()).thenReturn(new Context(Context.ContextType.string, "64b7f0c2a1d3e4f5a6b7c8f0"));
            when(currentStep.getData("context:groupConversationId")).thenAnswer(inv -> groupContext);
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(fileCalc("c1")), text("could not send"));

            orchestrator.executeIfToolsEnabled(chatModel, "sys", List.of(UserMessage.from("hi")), calculatorTask(), memory, exemptAll(), 0);

            verify(calculatorTool, never()).calculate(anyString());
            assertTrue(resultsSentToModel(chatModel).stream().anyMatch(r -> r.contains(MandatoryApprovals.UNPAUSABLE_GROUP_TURN)));
        }
    }

    @Nested
    @DisplayName("assembly")
    class Assembly {

        @Test
        @DisplayName("a provider whose rules cannot be built loses its tools too")
        void failingRulesDropTools() {
            var spec = ToolSpecification.builder().name("sendToPeer").description("d").build();
            ToolExecutor executor = (request, id) -> "sent";
            ToolSourceProvider broken = new ToolSourceProvider() {
                @Override
                public String source() {
                    return "a2a";
                }

                @Override
                public ToolContribution contribute(ToolAssemblyContext ctx) {
                    return new ToolContribution(List.of(spec), Map.of("sendToPeer", executor), Map.of(), Map.of());
                }

                @Override
                public List<MandatoryApprovalRule> mandatoryApprovals(ToolAssemblyContext ctx) {
                    throw new IllegalStateException("cannot build rules");
                }
            };
            var merger = ToolSourceRegistry.newMerger().add(broken, null);

            assertTrue(merger.build().specs().isEmpty());
            assertTrue(merger.mandatoryApprovalRules().isEmpty());
        }

        @Test
        @DisplayName("the reply tool and the LAZY completion keep the rules")
        void rulesSurviveSetupRebuilds() {
            var setup = new AgentOrchestrator.ToolSetup(new ArrayList<>(), Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of())
                    .withMandatoryApprovalRules(List.of(CONSENT));
            var task = calculatorTask();
            task.setAnswerMode("tool");

            assertEquals(List.of(CONSENT), AgentOrchestrator.withAnswerTool(setup, task).mandatoryApprovalRules());
            assertEquals(List.of(CONSENT), orchestrator.buildToolSetup(calculatorTask(), memory).mandatoryApprovalRules());
        }
    }

    @Nested
    @DisplayName("resumed loop")
    class Resumed {

        private PendingToolCallBatch.PendingToolCall pendingCall(String args, boolean mandatory) {
            var c = new PendingToolCallBatch.PendingToolCall();
            c.setCallId("c1");
            c.setToolName("calculate");
            c.setSource("builtin");
            c.setArgumentsRaw(args);
            if (mandatory) {
                c.setMandatoryReasonCode("file-egress");
                c.setMandatoryApprover(ApproverRule.CONVERSATION_USER.name());
            }
            return c;
        }

        private PendingToolCallBatch batch(PendingToolCallBatch.PendingToolCall call) {
            var batch = new PendingToolCallBatch();
            batch.setPauseEpoch("epoch-1");
            batch.setLlmTaskId("llmTask-A");
            batch.setActivatedToolNames(List.of());
            batch.setPauseCountThisTurn(1);
            batch.setCalls(List.of(call));
            batch.setTraceSoFar(new ArrayList<>());
            List<ChatMessage> transcript = new ArrayList<>();
            transcript.add(UserMessage.from("do it"));
            transcript.add(AiMessage.from(List.of(ToolExecutionRequest.builder().id("c1").name("calculate").arguments(call.getArgumentsRaw())
                    .build())));
            var res = new ChatTranscriptCodec().serialize(transcript, PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
            batch.setChatTranscriptJson(res.json());
            batch.setTranscriptOmitted(res.omitted());
            return batch;
        }

        private HitlDecision approval(MandatoryApprovalAttribution attribution) {
            var d = new HitlDecision();
            d.setVerdict(HitlDecision.HitlVerdict.APPROVED);
            d.setDecidedBy(ADMIN_ID);
            d.setMandatoryApproval(attribution);
            return d;
        }

        @Test
        @DisplayName("an approval the resume path never checked: NOT_EXECUTED, nothing claimed, the tool never runs")
        void uncheckedApprovalNotExecuted() throws Exception {
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("done"));

            var result = orchestrator.resumeToolLoop(chatModel, calculatorTask(), memory, batch(pendingCall("{\"expression\":\"file\"}", true)),
                    approval(null), true);

            verify(calculatorTool, never()).calculate(anyString());
            verify(journalStore, never()).tryClaim(anyString(), anyString(), anyString(), anyString(), anyString());
            assertTrue(result.trace().stream().anyMatch(t -> "hitl_mandatory_unapproved".equals(t.get("type"))));
        }

        @Test
        @DisplayName("checked approvals recorded per call, none for this call: NOT_EXECUTED despite a decision-wide attribution")
        void perCallApprovalMissingNotExecuted() throws Exception {
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("done"));
            var decision = approval(new MandatoryApprovalAttribution(USER_ID, null, ActingAs.USER));
            decision.setMandatoryApprovalsByCallId(Map.of("other-call", new MandatoryApprovalAttribution(USER_ID, null, ActingAs.USER)));

            var result = orchestrator.resumeToolLoop(chatModel, calculatorTask(), memory, batch(pendingCall("{\"expression\":\"file\"}", true)),
                    decision, true);

            verify(calculatorTool, never()).calculate(anyString());
            assertTrue(result.trace().stream().anyMatch(t -> "hitl_mandatory_unapproved".equals(t.get("type"))));
        }

        @Test
        @DisplayName("an administrator's checked approval: runs once, the call sees who approved and for whom, the trace records it")
        void adminApprovalRunsWithAttribution() throws Exception {
            var attribution = new MandatoryApprovalAttribution(ADMIN_ID, USER_ID, ActingAs.ADMIN_ON_BEHALF);
            when(journalStore.tryClaim(anyString(), eq("epoch-1"), eq("c1"), eq("calculate"), anyString())).thenReturn(true);
            // The "record the call creates": what the tool sees while it runs.
            AtomicReference<MandatoryApprovalAttribution> seen = new AtomicReference<>();
            when(calculatorTool.calculate("file")).thenAnswer(inv -> {
                seen.set(ApprovedCallScope.attribution().orElse(null));
                return "sent";
            });
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("done"));

            // As the resume path records it: per call, with a decision-wide value that is
            // another call's — the call must run with its own.
            var decision = approval(new MandatoryApprovalAttribution(ADMIN_ID, null, ActingAs.APPROVER));
            decision.setMandatoryApprovalsByCallId(Map.of("c1", attribution));

            var result = orchestrator.resumeToolLoop(chatModel, calculatorTask(), memory, batch(pendingCall("{\"expression\":\"file\"}", true)),
                    decision, true);

            verify(calculatorTool, times(1)).calculate("file");
            assertEquals(attribution, seen.get());
            assertFalse(ApprovedCallScope.attribution().isPresent(), "the binding ends with the call");
            var approved = result.trace().stream().filter(t -> "hitl_approved".equals(t.get("type"))).findFirst().orElseThrow();
            assertEquals("ADMIN_ON_BEHALF", approved.get("actingAs"));
            assertEquals(ADMIN_ID, approved.get("approvedBy"));
            assertEquals(USER_ID, approved.get("onBehalfOf"));
        }

        @Test
        @DisplayName("the model reissues the executed call: gated again as a repeat that still needs the user")
        void reissueIsGatedAgain() throws Exception {
            var attribution = new MandatoryApprovalAttribution(USER_ID, null, ActingAs.USER);
            when(journalStore.tryClaim(anyString(), eq("epoch-1"), eq("c1"), eq("calculate"), anyString())).thenReturn(true);
            when(calculatorTool.calculate("file")).thenReturn("sent");
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(toolBatch(fileCalc("c1")));

            var pause = assertThrows(ToolApprovalRequiredException.class, () -> orchestrator.resumeToolLoop(chatModel, calculatorTask(), memory,
                    batch(pendingCall("{\"expression\":\"file\"}", true)), approval(attribution), true));

            verify(calculatorTool, times(1)).calculate("file");
            var repeat = pause.getBatch().getCalls().get(0);
            assertEquals(ToolApprovalGate.REPEAT_REASON, repeat.getGateReason());
            assertEquals("CONVERSATION_USER", repeat.getMandatoryApprover());
        }

        @Test
        @DisplayName("an approver amends a pattern-gated call into one a rule matches: NOT_EXECUTED")
        void amendedIntoMandatory() throws Exception {
            ChatModel chatModel = mock(ChatModel.class);
            when(chatModel.chat(any(ChatRequest.class))).thenReturn(text("done"));
            var decision = approval(null);
            var cd = new ToolCallDecision();
            cd.setVerdict(HitlDecision.HitlVerdict.APPROVED);
            cd.setAmendedArguments("{\"expression\":\"file\"}");
            decision.setToolDecisions(Map.of("c1", cd));

            var result = orchestrator.resumeToolLoop(chatModel, calculatorTask(), memory, batch(pendingCall("{\"expression\":\"6*7\"}", false)),
                    decision, true);

            verify(calculatorTool, never()).calculate(anyString());
            assertTrue(result.trace().stream().anyMatch(t -> "hitl_mandatory_unapproved".equals(t.get("type"))));
            assertNotNull(result);
        }
    }
}
