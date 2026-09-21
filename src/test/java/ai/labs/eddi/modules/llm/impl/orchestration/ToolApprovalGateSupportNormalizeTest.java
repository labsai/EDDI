/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.orchestration;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolApprovalGateSupport#normalizeToolCallIds} must assign missing call
 * ids without dropping anything else on the message — in particular
 * {@link AiMessage#attributes()}, which carries Gemini 3.x's
 * {@code thoughtSignature} (and the key langchain4j's Anthropic and Bedrock
 * integrations use for signed thinking).
 */
@DisplayName("ToolApprovalGateSupport.normalizeToolCallIds")
class ToolApprovalGateSupportNormalizeTest {

    private static final String THINKING_SIGNATURE_KEY = "thinking_signature";
    private static final String SIGNATURE = "CtkBAcu98PBRSHOULDBEECHOEDBACKVERBATIM==";

    private static ToolApprovalsConfig gateActive() {
        var cfg = new ToolApprovalsConfig();
        cfg.setRequireApproval(List.of("*"));
        return cfg;
    }

    private static ToolExecutionRequest unidentified(String name) {
        return ToolExecutionRequest.builder().name(name).arguments("{}").build();
    }

    @Test
    @DisplayName("keeps attributes and thinking while assigning the missing ids")
    void keepsProviderOpaqueFields() {
        AiMessage fromProvider = AiMessage.builder()
                .text("Checking status now.")
                .thinking("The user asked about health; getStatus answers that.")
                .toolExecutionRequests(List.of(unidentified("getStatus")))
                .attributes(Map.of(THINKING_SIGNATURE_KEY, SIGNATURE))
                .build();

        AiMessage normalized = ToolApprovalGateSupport.normalizeToolCallIds(fromProvider, gateActive());

        assertNotNull(normalized.toolExecutionRequests().getFirst().id(), "the gate needs an addressable call id");
        assertTrue(normalized.toolExecutionRequests().getFirst().id().startsWith("gen-"));
        assertEquals(SIGNATURE, normalized.attribute(THINKING_SIGNATURE_KEY, String.class),
                "dropping this is what made the follow-up request unreplayable on Gemini 3.x");
        assertEquals("The user asked about health; getStatus answers that.", normalized.thinking());
        assertEquals("Checking status now.", normalized.text());
    }

    @Test
    @DisplayName("blank text still collapses to null, as the previous rebuild did")
    void blankTextStillCollapsesToNull() {
        // The message is replayed to the provider: a whitespace-only text would go
        // out as its own text part, which Anthropic rejects. Only the dropping of
        // attributes and thinking was meant to change.
        AiMessage blank = AiMessage.builder()
                .text("   ")
                .toolExecutionRequests(List.of(unidentified("getStatus")))
                .attributes(Map.of(THINKING_SIGNATURE_KEY, SIGNATURE))
                .build();

        AiMessage normalized = ToolApprovalGateSupport.normalizeToolCallIds(blank, gateActive());

        assertNull(normalized.text());
        assertEquals(SIGNATURE, normalized.attribute(THINKING_SIGNATURE_KEY, String.class));
    }

    @Test
    @DisplayName("returns the very same instance when nothing needs normalising")
    void untouchedWhenNothingToDo() {
        AiMessage alreadyIdentified = AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder().id("c1").name("getStatus").arguments("{}").build()))
                .attributes(Map.of(THINKING_SIGNATURE_KEY, SIGNATURE))
                .build();

        assertSame(alreadyIdentified, ToolApprovalGateSupport.normalizeToolCallIds(alreadyIdentified, gateActive()));
        // Gate inert — the pre-HITL path, byte-identical.
        assertSame(alreadyIdentified, ToolApprovalGateSupport.normalizeToolCallIds(alreadyIdentified, null));
        assertSame(alreadyIdentified,
                ToolApprovalGateSupport.normalizeToolCallIds(alreadyIdentified, new ToolApprovalsConfig()));
    }

    @Test
    @DisplayName("ids already present are left as the provider issued them")
    void mixedIdsPreserveTheProviderOnes() {
        AiMessage mixed = AiMessage.builder()
                .toolExecutionRequests(List.of(
                        ToolExecutionRequest.builder().id("provider-1").name("getStatus").arguments("{}").build(),
                        unidentified("deployAgent")))
                .attributes(Map.of(THINKING_SIGNATURE_KEY, SIGNATURE))
                .build();

        AiMessage normalized = ToolApprovalGateSupport.normalizeToolCallIds(mixed, gateActive());

        assertEquals("provider-1", normalized.toolExecutionRequests().get(0).id());
        assertTrue(normalized.toolExecutionRequests().get(1).id().startsWith("gen-"));
        assertEquals(SIGNATURE, normalized.attribute(THINKING_SIGNATURE_KEY, String.class));
    }
}
