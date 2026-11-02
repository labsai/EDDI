/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ConvergenceConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.ServiceJudgeConfig;
import ai.labs.eddi.configs.groups.mongo.AgentGroupStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ServiceJudgeValidation — save-time rules for convergence.service")
class ServiceJudgeValidationTest {

    private static ConvergenceConfig service(Map<String, String> parameters) {
        return new ConvergenceConfig(true, 2, 0.8, "SERVICE", null, new ServiceJudgeConfig("openai", "gpt-4o-mini", parameters, null, null, null));
    }

    private static List<String> problems(ConvergenceConfig convergence) {
        return ServiceJudgeValidation.problems("phases[0].convergence", convergence, "g");
    }

    @Test
    @DisplayName("a literal API key is refused, and the message never quotes it")
    void literalKeyRefused() {
        var problems = problems(service(Map.of("apiKey", "sk-plaintextplaintextplaintext")));

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("phases[0].convergence.service.parameters.apiKey"), problems.getFirst());
        assertFalse(problems.getFirst().contains("sk-plaintext"), problems.getFirst());
    }

    @Test
    @DisplayName("other credential-named keys are refused too")
    void otherCredentialNames() {
        assertFalse(problems(service(Map.of("secretKey", "abc123"))).isEmpty());
        assertFalse(problems(service(Map.of("accessKey", "abc123"))).isEmpty());
        assertFalse(problems(service(Map.of("token", "abc123"))).isEmpty());
        assertFalse(problems(service(Map.of("apiKey", "prefix-${vault:k}"))).isEmpty(), "a reference with literal text around it");
    }

    @Test
    @DisplayName("vault and vars references, and non-secret parameters, are accepted")
    void referencesAccepted() {
        assertTrue(problems(service(Map.of("apiKey", "${vault:openai-key}"))).isEmpty());
        assertTrue(problems(service(Map.of("apiKey", "${vars:judge-key}"))).isEmpty());
        assertTrue(problems(service(Map.of("baseUrl", "https://llm.example.com", "temperature", "0", "maxTokens", "200"))).isEmpty());
    }

    @Test
    @DisplayName("a connection reference is refused — model parameters do not accept it")
    void connectionRefused() {
        var problems = problems(service(Map.of("apiKey", "${connection:openai.apiKey}")));

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("${connection:...}"), problems.getFirst());
    }

    @Test
    @DisplayName("a service block naming only one of provider and model is refused")
    void halfConfigured() {
        var half = new ConvergenceConfig(true, 2, 0.8, "SERVICE", null, new ServiceJudgeConfig("openai", null, null, null, null, null));

        assertTrue(problems(half).stream().anyMatch(p -> p.contains("both llmProvider and llmModel")));
    }

    @Test
    @DisplayName("configs saved before the block existed still pass: SERVICE without a block, an unknown judge, MODERATOR")
    void legacyStillSaves() {
        assertTrue(problems(new ConvergenceConfig(true, 2, 0.8, "SERVICE")).isEmpty());
        assertTrue(problems(new ConvergenceConfig(true, 2, 0.8, "FOO")).isEmpty());
        assertTrue(problems(new ConvergenceConfig(true)).isEmpty());
        assertTrue(problems(null).isEmpty());
    }

    @Test
    @DisplayName("the store's save-time check reports it")
    void storeReportsIt() {
        var group = new AgentGroupConfiguration();
        group.setName("g");
        group.setPhases(List.of(new DiscussionPhase("Rounds", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL,
                AgentGroupConfiguration.ContextScope.FULL, false, null, 3, false, service(Map.of("apiKey", "literal-key-value")))));

        assertTrue(AgentGroupStore.memberAndLimitProblems(group).stream().anyMatch(p -> p.contains("plaintext credential")));
    }
}
