/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.ISecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@code setupAgent}'s openai-compatible {@code baseUrl} is held to the same
 * {@link OutboundTargetPolicy} the LLM builders apply later.
 */
@DisplayName("AgentSetupService — openai-compatible baseUrl under the outbound target policy")
class AgentSetupOpenAiCompatibleBaseUrlPolicyTest {

    private static final UrlValidationUtils.HostResolver RESOLVER = host -> switch (host) {
        case "vllm.corp.example" -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 0, 0, 5})};
        default -> throw new UnknownHostException(host);
    };

    private AgentSetupService service;

    @BeforeEach
    void setUp() {
        service = new AgentSetupService(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class), mock(ISecretProvider.class),
                "http://localhost:11434");
    }

    private static SetupAgentRequest request(String baseUrl) {
        return new SetupAgentRequest("Agent", "prompt", "openai-compatible", "granite", null, baseUrl, null, null, null, null, null, null, false,
                null, null, null);
    }

    @Test
    @DisplayName("policy rejects the URL: AgentSetupException naming baseUrl")
    void rejected() {
        service.targetPolicy = new OutboundTargetPolicy(true, null, RESOLVER);
        var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(request(" http://vllm.corp.example:8000/v1 ")));
        assertTrue(e.getMessage().startsWith("baseUrl refused:"), e.getMessage());
    }

    @Test
    @DisplayName("policy allows the URL: setup gets past the check")
    void allowed() {
        service.targetPolicy = new OutboundTargetPolicy(false, null, RESOLVER);
        var e = assertThrows(Exception.class, () -> service.setupAgent(request("http://vllm.corp.example:8000/v1")));
        assertFalse(String.valueOf(e.getMessage()).startsWith("baseUrl refused:"), e.getMessage());
    }
}
