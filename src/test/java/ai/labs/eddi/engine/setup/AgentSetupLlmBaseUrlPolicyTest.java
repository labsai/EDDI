/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.security.SelfUrlResolver;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.ISecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@code createApiAgent}'s {@code llmBaseUrl} is held to the same
 * {@link OutboundTargetPolicy} the LLM builders apply later, so a URL the model
 * build would refuse fails at the wizard instead, before any resource exists.
 * Every case fails before the first store call, so the stores are unprimed
 * mocks: reaching one would itself be a failure of the test's premise.
 */
@DisplayName("AgentSetupService — llmBaseUrl under the outbound target policy")
class AgentSetupLlmBaseUrlPolicyTest {

    private static final String SPEC = "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"T\",\"version\":\"1\"},\"paths\":{}}";

    private static final UrlValidationUtils.HostResolver RESOLVER = host -> switch (host) {
        case "llm.corp.example" -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 0, 0, 5})};
        case "api.example.com" -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34})};
        default -> throw new UnknownHostException(host);
    };

    private AgentSetupService service;

    @BeforeEach
    void setUp() {
        service = new AgentSetupService(mock(IRestInterfaceFactory.class), mock(IRestAgentAdministration.class), mock(ISecretProvider.class),
                "http://localhost:11434");
    }

    private static CreateApiAgentRequest request(String llmBaseUrl) {
        return new CreateApiAgentRequest("Agent", "prompt", SPEC, "ollama", "llama3", null, null, null, null, null, null, false, null,
                llmBaseUrl, null, null, null, null, null);
    }

    private static boolean refusedByPolicy(Throwable e) {
        return e.getMessage().startsWith("llmBaseUrl refused:");
    }

    @Test
    @DisplayName("protection on: a private LLM base URL is refused with the policy's reason")
    void protectionOnRefusesPrivate() {
        service.targetPolicy = new OutboundTargetPolicy(true, null, RESOLVER);
        var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.createApiAgent(request("http://llm.corp.example:11434")));
        assertTrue(refusedByPolicy(e) && e.getMessage().contains("private/internal"), e.getMessage());
        var loopback = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.createApiAgent(request("http://localhost:11434")));
        assertTrue(refusedByPolicy(loopback), loopback.getMessage());
    }

    @Test
    @DisplayName("protection on: a public URL and this deployment's own origin get past the check")
    void protectionOnAllowsPublicAndSelf() {
        service.targetPolicy = new OutboundTargetPolicy(true, new SelfUrlResolver(Optional.of("http://127.0.0.1:7070"), 7070), RESOLVER);
        // The request then proceeds into resource creation against the unprimed
        // mocks and fails there — anywhere but the policy check is the assertion.
        var publicUrl = assertThrows(Exception.class, () -> service.createApiAgent(request("https://api.example.com/v1")));
        assertFalse(refusedByPolicy(publicUrl), publicUrl.getMessage());
        var self = assertThrows(Exception.class, () -> service.createApiAgent(request("http://127.0.0.1:7070/v1")));
        assertFalse(refusedByPolicy(self), self.getMessage());
    }

    @Test
    @DisplayName("protection off, and nothing injected: a private URL passes, the metadata service does not")
    void protectionOffAllowsPrivate() {
        var nothingInjected = assertThrows(Exception.class, () -> service.createApiAgent(request("http://llm.corp.example:11434")));
        assertFalse(refusedByPolicy(nothingInjected), nothingInjected.getMessage());

        service.targetPolicy = new OutboundTargetPolicy(false, null, RESOLVER);
        var off = assertThrows(Exception.class, () -> service.createApiAgent(request("http://llm.corp.example:11434")));
        assertFalse(refusedByPolicy(off), off.getMessage());
        var metadata = assertThrows(AgentSetupService.AgentSetupException.class,
                () -> service.createApiAgent(request("http://169.254.169.254/v1")));
        assertTrue(refusedByPolicy(metadata) && metadata.getMessage().contains("instance-metadata"), metadata.getMessage());
    }

    @Test
    @DisplayName("a non-http URL is still refused by the scheme check first")
    void schemeCheckFirst() {
        service.targetPolicy = new OutboundTargetPolicy(true, null, RESOLVER);
        var e = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.createApiAgent(request("ftp://llm.corp.example")));
        assertTrue(e.getMessage().contains("valid http(s) URL"), e.getMessage());
    }
}
