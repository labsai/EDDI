/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.alerts.model.MatchOp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.ratingRule;
import static ai.labs.eddi.engine.alerts.AlertTestData.withThrottle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AlertRuleValidator")
class AlertRuleValidatorTest {

    private static boolean reports(AlertRule rule, String fragment) {
        return AlertRuleValidator.validate(rule).stream().anyMatch(p -> p.contains(fragment));
    }

    @Test
    @DisplayName("valid rules pass")
    void valid() {
        assertEquals(List.of(), AlertRuleValidator.validate(ratingRule(0.25, true, List.of("Too slow"), DeliveryTarget.slack("C0123ABC"))));
        assertEquals(List.of(), AlertRuleValidator.validate(errorRule(List.of(ErrorKind.TURN_ERROR),
                DeliveryTarget.webhook("https://hooks.example.com/x", "${vault:alert-hook}"))));
    }

    @Test
    @DisplayName("each source needs its own criteria and refuses the other's")
    void conditions() {
        assertTrue(reports(ratingRule(null, null, null), "a RATING rule needs"));
        assertTrue(reports(ratingRule(null, false, List.of()), "a RATING rule needs"));
        assertTrue(reports(ratingRule(1.5, null, null), "between 0 and 1"));
        assertTrue(reports(errorRule(List.of()), "needs condition.errorKinds"));
        AlertRule mixed = new AlertRule(null, "x", true, AlertScope.agent(AGENT), AlertSource.CONVERSATION_ERROR,
                new AlertCondition(0.5, null, null, List.of(ErrorKind.TURN_ERROR)), List.of(), null, null, null, null, null, null);
        assertTrue(reports(mixed, "belong to RATING rules"));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR, ErrorKind.TURN_ERROR)), "must not repeat"));
    }

    @Test
    @DisplayName("scope: one of allAgents or agentIds, no blanks or repeats")
    void scope() {
        AlertRule base = errorRule(List.of(ErrorKind.TURN_ERROR));
        assertTrue(reports(new AlertRule(null, "x", true, new AlertScope(false, List.of()), base.source(), base.condition(), List.of(), null,
                null, null, null, null, null), "at least one agent"));
        assertTrue(reports(new AlertRule(null, "x", true, new AlertScope(true, List.of(AGENT)), base.source(), base.condition(), List.of(),
                null, null, null, null, null, null), "not both"));
        assertTrue(reports(new AlertRule(null, "x", true, new AlertScope(false, List.of(AGENT, AGENT)), base.source(), base.condition(),
                List.of(), null, null, null, null, null, null), "twice"));
    }

    @Test
    @DisplayName("webhooks: https only, not local, no credentials in the URL, a vault reference for the secret")
    void webhooks() {
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("http://hooks.example.com/x", "${vault:k}")),
                "must be an https URL"));
        assertEquals(List.of(),
                AlertRuleValidator
                        .validate(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("https://hooks.example.com/x", "${vault:k}"))));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("ftp://x.example.com", "${vault:k}")),
                "Only http and https"));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("http://localhost:8080/x", "${vault:k}")),
                "local or internal"));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("https://u:p@x.example.com/", "${vault:k}")),
                "credentials"));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("https://x.example.com/", "plain-secret")),
                "${vault:...} reference"));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("https://x.example.com/", null)),
                "${vault:...} reference"));
    }

    @Test
    @DisplayName("Slack channel ids, the target cap and repeated targets")
    void targets() {
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("#general")), "Slack channel id"));
        DeliveryTarget s = DeliveryTarget.slack("C0123ABC");
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), s, s), "repeats an earlier target"));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.slack("C1AA"), DeliveryTarget.slack("C2AA"),
                DeliveryTarget.slack("C3AA"), DeliveryTarget.slack("C4AA"), DeliveryTarget.slack("C5AA"), DeliveryTarget.slack("C6AA")),
                "at most 5"));
    }

    @Test
    @DisplayName("throttle bounds and the name")
    void throttleAndName() {
        assertTrue(reports(withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR)), new AlertRule.Throttle(0, 10), null), "windowMinutes"));
        assertTrue(reports(withThrottle(errorRule(List.of(ErrorKind.TURN_ERROR)), new AlertRule.Throttle(60, 0), null), "maxPerWindow"));
        AlertRule base = errorRule(List.of(ErrorKind.TURN_ERROR));
        assertTrue(reports(new AlertRule(null, " ", true, base.scope(), base.source(), base.condition(), List.of(), null, null, null, null,
                null, null), "name is required"));
    }

    @Test
    @DisplayName("a webhook URL may be a vault reference; the masked value a read returns is never accepted")
    void vaultAndMaskedUrls() {
        assertEquals(List.of(), AlertRuleValidator.validate(errorRule(List.of(ErrorKind.TURN_ERROR),
                DeliveryTarget.webhook("${vault:alert-hook-url}", "${vault:k}"))));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("https://hooks.example.com/***", "${vault:k}")),
                "masked value"));
        assertTrue(reports(errorRule(List.of(ErrorKind.TURN_ERROR), DeliveryTarget.webhook("${vault:broken", "${vault:k}")),
                "vault reference must be"));
    }

    @Test
    @DisplayName("OUTPUT_MATCH: the matcher's checks apply, and its fields belong to no other source")
    void outputMatch() {
        AlertRule base = errorRule(List.of(ErrorKind.TURN_ERROR));
        AlertRule ok = new AlertRule(null, "x", true, base.scope(), AlertSource.OUTPUT_MATCH,
                AlertCondition.outputMatch("properties.escalate", MatchOp.EQ, true, true), List.of(), null, null, null, null, null, null);
        assertEquals(List.of(), AlertRuleValidator.validate(ok));
        AlertRule badPath = new AlertRule(null, "x", true, base.scope(), AlertSource.OUTPUT_MATCH,
                AlertCondition.outputMatch("properties.*", MatchOp.EQ, true, null), List.of(), null, null, null, null, null, null);
        assertTrue(reports(badPath, "condition.path"));
        AlertRule mixed = new AlertRule(null, "x", true, base.scope(), AlertSource.CONVERSATION_ERROR,
                new AlertCondition(null, null, null, List.of(ErrorKind.TURN_ERROR), "properties.x", MatchOp.EXISTS, null, null), List.of(), null,
                null, null, null, null, null);
        assertTrue(reports(mixed, "belong to OUTPUT_MATCH rules"));
        AlertRule ratingFieldsOnOutput = new AlertRule(null, "x", true, base.scope(), AlertSource.OUTPUT_MATCH,
                new AlertCondition(0.5, null, null, null, "properties.x", MatchOp.EXISTS, null, null), List.of(), null, null, null, null, null, null);
        assertTrue(reports(ratingFieldsOnOutput, "do not belong to OUTPUT_MATCH"));
    }
}
