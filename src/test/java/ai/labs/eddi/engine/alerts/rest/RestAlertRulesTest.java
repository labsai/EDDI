/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.alerts.AlertMaintenanceSchedule;
import ai.labs.eddi.engine.alerts.AlertRuleCache;
import ai.labs.eddi.engine.alerts.AlertSenders;
import ai.labs.eddi.engine.alerts.AlertSenders.AlertDeliveryException;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.api.IRestAlertRules.TestResult;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ai.labs.eddi.engine.alerts.AlertTestData.AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.NOW;
import static ai.labs.eddi.engine.alerts.AlertTestData.OTHER_AGENT;
import static ai.labs.eddi.engine.alerts.AlertTestData.RULE_ID;
import static ai.labs.eddi.engine.alerts.AlertTestData.errorRule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("/alerts/rules")
class RestAlertRulesTest {

    private static final DeliveryTarget SLACK = DeliveryTarget.slack("C0123ABC");
    private static final DeliveryTarget HOOK = DeliveryTarget.webhook("https://hooks.example.com/secret-path?token=t0k3n", "${vault:hook}");

    private ResourceAccessGuard guard;
    private IAlertRuleStore store;
    private AlertRuleCache cache;
    private AlertSenders senders;
    private AlertMaintenanceSchedule schedule;
    private RestAlertRules rest;

    @BeforeEach
    void setUp() {
        guard = mock(ResourceAccessGuard.class);
        ConversationAccessGuard conversationGuard = mock(ConversationAccessGuard.class);
        when(conversationGuard.callerActor(anyString())).thenReturn("erin");
        store = mock(IAlertRuleStore.class);
        cache = mock(AlertRuleCache.class);
        senders = mock(AlertSenders.class);
        schedule = mock(AlertMaintenanceSchedule.class);
        when(store.create(any())).thenAnswer(inv -> ((AlertRule) inv.getArgument(0)).withId(RULE_ID));
        when(store.update(any())).thenReturn(true);
        rest = new RestAlertRules(store, cache, new AlertAccess(guard, conversationGuard), senders, schedule, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void asEditor() {
        when(guard.seesEverything()).thenReturn(false);
    }

    private void asAdmin() {
        when(guard.seesEverything()).thenReturn(true);
    }

    private static AlertRule scoped(AlertScope scope, DeliveryTarget... targets) {
        AlertRule base = errorRule(List.of(ErrorKind.TURN_ERROR), targets);
        return new AlertRule(base.id(), base.name(), true, scope, base.source(), base.condition(), base.delivery(), null, null, "admin", NOW,
                "admin", NOW);
    }

    @SuppressWarnings("unchecked")
    private static void assertError(int status, String code, Executable call) {
        WebApplicationException e = assertThrows(WebApplicationException.class, call);
        assertEquals(status, e.getResponse().getStatus());
        assertEquals(code, ((Map<String, String>) e.getResponse().getEntity()).get("error"));
    }

    @Nested
    @DisplayName("an editor")
    class Editor {

        @Test
        @DisplayName("creates a single-agent Slack rule on an agent they hold EDIT on; the author is the caller, not the body")
        void createsOwnRule() {
            asEditor();
            AlertRule body = new AlertRule("ignored", "x", true, AlertScope.agent(AGENT), errorRule(List.of(ErrorKind.TURN_ERROR)).source(),
                    errorRule(List.of(ErrorKind.TURN_ERROR)).condition(), List.of(SLACK), null, null, "mallory", NOW.minusSeconds(999),
                    "mallory", NOW.minusSeconds(999));

            assertEquals(201, rest.createRule(body).getStatus());

            verify(guard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
            ArgumentCaptor<AlertRule> stored = ArgumentCaptor.forClass(AlertRule.class);
            verify(store).create(stored.capture());
            assertNull(stored.getValue().id());
            assertEquals("erin", stored.getValue().createdBy());
            assertEquals(NOW, stored.getValue().createdAt());
            verify(cache).invalidate();
            verify(schedule).ensure();
        }

        @Test
        @DisplayName("may not write a rule over every agent, or several — ADMIN_SCOPE_REQUIRED, nothing stored")
        void scopeIsAdmins() {
            asEditor();
            assertError(403, AlertErrors.ADMIN_SCOPE_REQUIRED, () -> rest.createRule(scoped(new AlertScope(true, List.of()), SLACK)));
            assertError(403, AlertErrors.ADMIN_SCOPE_REQUIRED, () -> rest.createRule(scoped(new AlertScope(false, List.of(AGENT, OTHER_AGENT)))));
            verify(store, never()).create(any());
        }

        @Test
        @DisplayName("may not add a webhook target — WEBHOOK_ADMIN_ONLY")
        void webhookIsAdmins() {
            asEditor();
            assertError(403, AlertErrors.WEBHOOK_ADMIN_ONLY, () -> rest.createRule(scoped(AlertScope.agent(AGENT), HOOK)));
            assertError(403, AlertErrors.WEBHOOK_ADMIN_ONLY, () -> rest.testRule(scoped(AlertScope.agent(AGENT), HOOK)));
            verify(store, never()).create(any());
        }

        @Test
        @DisplayName("without EDIT on the agent: refused before anything is stored")
        void needsEdit() {
            asEditor();
            doThrow(new ForbiddenException("no")).when(guard).requireAccess(AGENT, AccessLevel.EDIT, "agent");
            assertThrows(ForbiddenException.class, () -> rest.createRule(scoped(AlertScope.agent(AGENT), SLACK)));
            verify(store, never()).create(any());
        }

        @Test
        @DisplayName("update and delete re-check the STORED rule: an editor cannot take over an administrator's every-agent rule")
        void recheckStored() {
            asEditor();
            when(store.read(RULE_ID)).thenReturn(Optional.of(scoped(new AlertScope(true, List.of()), SLACK)));
            assertError(403, AlertErrors.ADMIN_SCOPE_REQUIRED, () -> rest.updateRule(RULE_ID, scoped(AlertScope.agent(AGENT), SLACK)));
            assertError(403, AlertErrors.ADMIN_SCOPE_REQUIRED, () -> rest.deleteRule(RULE_ID));
            verify(store, never()).update(any());
            verify(store, never()).delete(any());
        }

        @Test
        @DisplayName("update of a rule on an agent they lost EDIT on is refused even when the new body names an agent they hold")
        void recheckStoredAgent() {
            asEditor();
            when(store.read(RULE_ID)).thenReturn(Optional.of(scoped(AlertScope.agent(OTHER_AGENT), SLACK)));
            doThrow(new ForbiddenException("no")).when(guard).requireAccess(OTHER_AGENT, AccessLevel.EDIT, "agent");
            assertThrows(ForbiddenException.class, () -> rest.updateRule(RULE_ID, scoped(AlertScope.agent(AGENT), SLACK)));
            verify(store, never()).update(any());
        }

        @Test
        @DisplayName("lists only the rules of the agent they name, and sees a webhook by host only, never its path, token or secret")
        void listAndRedact() {
            asEditor();
            assertError(400, AlertErrors.AGENT_ID_REQUIRED, () -> rest.listRules(null, 0, 20));
            when(store.list(AGENT, true, 0, 20)).thenReturn(List.of(scoped(AlertScope.agent(AGENT), HOOK)));
            when(store.count(AGENT, true)).thenReturn(1L);

            AlertRule seen = rest.listRules(AGENT, 0, 20).rules().get(0);

            assertEquals("https://hooks.example.com/…", seen.delivery().get(0).url());
            assertNull(seen.delivery().get(0).signingSecret());
            verify(store).list(AGENT, true, 0, 20);
        }

        @Test
        @DisplayName("cannot read an every-agent rule")
        void readAdminRule() {
            asEditor();
            when(store.read(RULE_ID)).thenReturn(Optional.of(scoped(new AlertScope(true, List.of()))));
            assertError(403, AlertErrors.ADMIN_SCOPE_REQUIRED, () -> rest.readRule(RULE_ID));
        }
    }

    @Nested
    @DisplayName("an administrator")
    class Admin {

        @Test
        @DisplayName("writes every-agent rules with webhooks, and sees them whole")
        void anything() {
            asAdmin();
            rest.createRule(scoped(new AlertScope(true, List.of()), HOOK, SLACK));
            verify(store).create(any());
            verify(guard, never()).requireAccess(anyString(), any(), anyString());
            when(store.list(null, false, 0, 20)).thenReturn(List.of(scoped(new AlertScope(true, List.of()), HOOK)));
            assertEquals(HOOK.url(), rest.listRules(null, 0, 20).rules().get(0).delivery().get(0).url());
        }

        @Test
        @DisplayName("an invalid rule is INVALID_RULE naming every problem; nothing stored")
        void invalid() {
            asAdmin();
            AlertRule bad = scoped(new AlertScope(false, List.of()), DeliveryTarget.slack("#general"));
            WebApplicationException e = assertThrows(WebApplicationException.class, () -> rest.createRule(bad));
            @SuppressWarnings("unchecked")
            String message = ((Map<String, String>) e.getResponse().getEntity()).get("message");
            assertTrue(message.contains("scope.agentIds") && message.contains("Slack channel id"), message);
            verify(store, never()).create(any());
        }

        @Test
        @DisplayName("a test send reports per target and stores nothing")
        void testSend() throws Exception {
            asAdmin();
            doThrow(new AlertDeliveryException("The webhook answered HTTP 404")).when(senders).send(any(), eq(HOOK));
            TestResult result = rest.testRule(scoped(AlertScope.agent(AGENT), SLACK, HOOK));
            assertTrue(result.deliveries().get(0).delivered());
            assertFalse(result.deliveries().get(1).delivered());
            assertEquals("The webhook answered HTTP 404", result.deliveries().get(1).error());
            verify(store, never()).create(any());
            verify(store, never()).list(any(), anyBoolean(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("update keeps the original author, stamps the editor; an unknown id is RULE_NOT_FOUND")
        void update() {
            asAdmin();
            when(store.read(RULE_ID)).thenReturn(Optional.of(scoped(AlertScope.agent(AGENT), SLACK)));
            AlertRule updated = rest.updateRule(RULE_ID, scoped(AlertScope.agent(AGENT), SLACK));
            assertEquals("admin", updated.createdBy());
            assertEquals("erin", updated.updatedBy());
            assertError(404, AlertErrors.RULE_NOT_FOUND, () -> rest.updateRule("6aba70011a25f8be7920e0ff", updated));
        }
    }
}
