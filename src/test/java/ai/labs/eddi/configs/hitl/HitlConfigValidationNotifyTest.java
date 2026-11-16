/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.hitl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.hitl.model.HitlNotifyConfig;
import ai.labs.eddi.engine.mail.NotifyRecipientPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Save-time validation of {@code hitlConfig.notify.email}. */
class HitlConfigValidationNotifyTest {

    private static AgentConfiguration.HitlConfig withRecipients(List<String> recipients) {
        var email = new HitlNotifyConfig.Email();
        email.setRecipients(recipients);
        var notify = new HitlNotifyConfig();
        notify.setEmail(email);
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setNotify(notify);
        return hitl;
    }

    private static String refusal(List<String> recipients) {
        return assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(withRecipients(recipients))).getMessage();
    }

    @Test
    @DisplayName("an old config without notify, and notify without email, are unchanged")
    void absentIsFine() throws Exception {
        assertDoesNotThrow(() -> HitlConfigValidation.validate(new AgentConfiguration.HitlConfig()));
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setNotify(new HitlNotifyConfig());
        assertDoesNotThrow(() -> HitlConfigValidation.validate(hitl));

        var parsed = new ObjectMapper().readValue("{\"hitlConfig\":{\"timeoutPolicy\":\"AUTO_REJECT\",\"approvalTimeout\":\"PT15M\"}}",
                AgentConfiguration.class);
        assertNull(parsed.getHitlConfig().getNotify());
    }

    @Test
    @DisplayName("valid recipients pass, and the JSON shape round-trips")
    void valid() throws Exception {
        assertDoesNotThrow(() -> HitlConfigValidation.validate(withRecipients(List.of("ops@example.com", "Approvals@Example.COM"))));

        var parsed = new ObjectMapper().readValue("{\"hitlConfig\":{\"notify\":{\"email\":{\"recipients\":[\"ops@example.com\"]}}}}",
                AgentConfiguration.class);
        assertEquals(List.of("ops@example.com"), parsed.getHitlConfig().getNotify().getEmail().getRecipients());
    }

    @Test
    @DisplayName("an email block without recipients is refused rather than silently notifying nobody")
    void emptyRefused() {
        assertTrue(refusal(List.of()).contains("hitlConfig.notify.email.recipients"));
        assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(withRecipients(null)));
    }

    @Test
    @DisplayName("at most 20 recipients")
    void capped() {
        List<String> twenty = new ArrayList<>();
        for (int i = 0; i < HitlConfigValidation.MAX_NOTIFY_RECIPIENTS; i++) {
            twenty.add("r" + i + "@example.com");
        }
        assertDoesNotThrow(() -> HitlConfigValidation.validate(withRecipients(twenty)));
        List<String> twentyOne = new ArrayList<>(twenty);
        twentyOne.add("one-too-many@example.com");
        assertTrue(refusal(twentyOne).contains("at most 20"));
    }

    @Test
    @DisplayName("malformed, display-name, non-ASCII and header-injecting entries are refused without echoing them")
    void malformed() {
        for (String bad : List.of("not-an-address", "Ops <ops@example.com>", "jürgen@example.com", "ops@example.com\r\nBcc: x@evil.test",
                "ops@localhost", "")) {
            String message = refusal(List.of("fine@example.com", bad));
            assertTrue(message.contains("recipients[1]"), message);
            if (!bad.isEmpty()) {
                assertFalse(message.contains(bad), "the refusal must not echo the address: " + message);
            }
        }
    }

    @Test
    @DisplayName("duplicates are found after normalisation")
    void duplicates() {
        assertTrue(refusal(List.of("ops@example.com", "OPS@example.com")).contains("repeats"));
    }

    @Test
    @DisplayName("with a mailer configured, a recipient outside the operator's domains is refused at save time")
    void outsidePolicyRefusedWithMailer() {
        var previous = HitlConfigValidation.recipientPolicySource;
        HitlConfigValidation.recipientPolicySource = () -> new NotifyRecipientPolicy.Current(true,
                NotifyRecipientPolicy.of(List.of(), "eddi@example.com"));
        try {
            assertDoesNotThrow(() -> HitlConfigValidation.validate(withRecipients(List.of("ops@example.com", "lead@eu.example.com"))));
            String message = refusal(List.of("ops@example.com", "someone@gmail.com"));
            assertTrue(message.contains("recipients[1]") && message.contains(NotifyRecipientPolicy.ALLOWED_DOMAINS), message);
            assertFalse(message.contains("someone@gmail.com"), message);
        } finally {
            HitlConfigValidation.recipientPolicySource = previous;
        }
    }

    @Test
    @DisplayName("without a mailer it is accepted (warned): the operator may configure the policy later")
    void outsidePolicyAcceptedWithoutMailer() {
        var previous = HitlConfigValidation.recipientPolicySource;
        HitlConfigValidation.recipientPolicySource = () -> new NotifyRecipientPolicy.Current(false, NotifyRecipientPolicy.of(List.of(), null));
        try {
            assertDoesNotThrow(() -> HitlConfigValidation.validate(withRecipients(List.of("someone@gmail.com"))));
        } finally {
            HitlConfigValidation.recipientPolicySource = previous;
        }
    }

    private static AgentConfiguration.HitlConfig withSlack(String integration) {
        var slack = new HitlNotifyConfig.Slack();
        slack.setIntegration(integration);
        var notify = new HitlNotifyConfig();
        notify.setSlack(slack);
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setNotify(notify);
        return hitl;
    }

    @Test
    @DisplayName("notify.slack: a named integration passes, and the JSON shape round-trips")
    void slackValid() throws Exception {
        assertDoesNotThrow(() -> HitlConfigValidation.validate(withSlack("ops-slack")));

        var parsed = new ObjectMapper().readValue("{\"hitlConfig\":{\"notify\":{\"slack\":{\"integration\":\"ops-slack\"}}}}",
                AgentConfiguration.class);
        assertEquals("ops-slack", parsed.getHitlConfig().getNotify().getSlack().getIntegration());
    }

    @Test
    @DisplayName("notify.slack: blank, '|' and over-long names are refused with the field path")
    void slackRefused() {
        for (String bad : new String[]{null, " ", "a|b", "x".repeat(HitlConfigValidation.MAX_NOTIFY_INTEGRATION_NAME + 1)}) {
            String message = assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(withSlack(bad))).getMessage();
            assertTrue(message.startsWith("hitlConfig.notify.slack.integration"), message);
        }
        assertDoesNotThrow(() -> HitlConfigValidation.validate(withSlack("x".repeat(HitlConfigValidation.MAX_NOTIFY_INTEGRATION_NAME))));
    }

    @Test
    @DisplayName("groups: notify.slack is validated, notify.email is refused rather than never sent")
    void groupNotify() {
        var group = new AgentGroupConfiguration.HitlConfig();
        group.setNotify(withSlack("ops-slack").getNotify());
        assertDoesNotThrow(() -> HitlConfigValidation.validate(group));

        group.setNotify(withSlack("a|b").getNotify());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(group)).getMessage()
                .startsWith("hitlConfig.notify.slack.integration"));

        group.setNotify(withRecipients(List.of("ops@example.com")).getNotify());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HitlConfigValidation.validate(group)).getMessage()
                .contains("hitlConfig.notify.email is not supported for groups"));
    }
}
