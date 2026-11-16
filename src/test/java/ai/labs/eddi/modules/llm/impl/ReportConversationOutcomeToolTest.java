/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.health.HealthConfigPolicy;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ToolCacheService;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.ReportConversationOutcomeTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("report_conversation_outcome — gating, validation and what it writes")
class ReportConversationOutcomeToolTest {

    private final HealthConfigPolicy policy = mock(HealthConfigPolicy.class);
    private final IConversationMemory memory = mock(IConversationMemory.class);
    private final IWritableConversationStep step = mock(IWritableConversationStep.class);

    private ReportConversationOutcomeTool template() {
        return new ReportConversationOutcomeTool(policy);
    }

    {
        when(memory.getAgentId()).thenReturn("agent");
        when(memory.getAgentVersion()).thenReturn(4);
        when(memory.getCurrentStep()).thenReturn(step);
    }

    @Nested
    @DisplayName("the tool")
    class TheTool {

        @Test
        @DisplayName("stores the claimed outcome, upper-cased, under health:self_reported_outcome")
        @SuppressWarnings({"unchecked", "rawtypes"})
        void writesTheClaim() {
            String answer = template().boundTo(memory).reportConversationOutcome(" resolved ");
            assertEquals("Recorded: RESOLVED.", answer);
            ArgumentCaptor<IData> data = ArgumentCaptor.forClass(IData.class);
            verify(step).storeData(data.capture());
            assertEquals(MemoryKeys.HEALTH_SELF_REPORTED_OUTCOME, data.getValue().getKey());
            assertEquals("RESOLVED", data.getValue().getResult());
        }

        @Test
        @DisplayName("refuses anything but RESOLVED, ESCALATED, DEFLECTED, FAILED — an assumption or free text is not a claim")
        void validates() {
            ReportConversationOutcomeTool bound = template().boundTo(memory);
            for (String bad : new String[]{"ASSUMED_RESOLVED", "ABANDONED", "OPEN", "the user was happy", "", null}) {
                assertTrue(bound.reportConversationOutcome(bad).startsWith("Error"), String.valueOf(bad));
            }
            verify(step, never()).storeData(any());
            for (String good : new String[]{"ESCALATED", "deflected", "Failed"}) {
                assertTrue(bound.reportConversationOutcome(good).startsWith("Recorded"), good);
            }
            verify(step, times(3)).storeData(any());
        }

        @Test
        @DisplayName("the unbound catalog bean refuses every call")
        void unbound() {
            assertTrue(template().reportConversationOutcome("RESOLVED").startsWith("Error"));
        }

        @Test
        @DisplayName("takes no reason: its only parameter is the outcome, and it is conversation-bound (never cached across conversations)")
        void noReasonParameter() throws Exception {
            var method = ReportConversationOutcomeTool.class.getMethod("reportConversationOutcome", String.class);
            assertEquals(1, method.getParameterCount());
            assertEquals("report_conversation_outcome", method.getAnnotation(Tool.class).name());
            assertTrue(ToolCacheService.isConversationBound(ReportConversationOutcomeTool.class));
        }

        @Test
        @DisplayName("enabledFor asks the version's healthConfig")
        void enabledFor() {
            when(policy.selfReportToolEnabled("agent", 4)).thenReturn(true);
            assertTrue(template().enabledFor(memory));
            when(policy.selfReportToolEnabled("agent", 4)).thenReturn(false);
            assertFalse(template().enabledFor(memory));
            assertFalse(template().enabledFor(null));
        }
    }

    @Nested
    @DisplayName("BuiltinToolsProvider gating")
    class Gating {

        private BuiltinToolsProvider provider(ReportConversationOutcomeTool tool) {
            return new BuiltinToolsProvider(mock(CalculatorTool.class), mock(DateTimeTool.class), mock(WebSearchTool.class),
                    mock(DataFormatterTool.class), mock(WebScraperTool.class), mock(TextSummarizerTool.class), mock(PdfReaderTool.class),
                    mock(WeatherTool.class), mock(FetchToolResponsePageTool.class), () -> tool);
        }

        private ToolAssemblyContext ctx(List<String> whitelist, boolean builtIns) {
            var task = new LlmConfiguration.Task();
            task.setEnableBuiltInTools(builtIns);
            task.setBuiltInToolsWhitelist(whitelist);
            return new ToolAssemblyContext(memory, task, whitelist, null, "user-1", "agent", null);
        }

        private long reporters(List<Object> tools) {
            return tools.stream().filter(ReportConversationOutcomeTool.class::isInstance).count();
        }

        @Test
        @DisplayName("offered when the version enables it and the task has built-ins (no whitelist, or one naming it)")
        void offered() {
            when(policy.selfReportToolEnabled("agent", 4)).thenReturn(true);
            assertEquals(1, reporters(provider(template()).collect(ctx(null, true))));
            assertEquals(1, reporters(provider(template()).collect(ctx(List.of("report_conversation_outcome"), true))));
            assertEquals(1, provider(template()).contribute(ctx(List.of("report_conversation_outcome"), true)).specs().size());
        }

        @Test
        @DisplayName("not offered: version opt-out, a whitelist without it, built-ins off, or no bean")
        void notOffered() {
            when(policy.selfReportToolEnabled("agent", 4)).thenReturn(false);
            assertEquals(0, reporters(provider(template()).collect(ctx(null, true))), "healthConfig.selfReportTool is false");
            when(policy.selfReportToolEnabled("agent", 4)).thenReturn(true);
            assertEquals(0, reporters(provider(template()).collect(ctx(List.of("calculator"), true))));
            assertTrue(provider(template()).contribute(ctx(null, false)).specs().isEmpty(), "enableBuiltInTools off");
            assertEquals(0, reporters(provider(null).collect(ctx(null, true))));
        }
    }
}
