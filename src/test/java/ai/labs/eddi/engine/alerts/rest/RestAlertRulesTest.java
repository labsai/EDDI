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
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.times;
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

            assertEquals("https://hooks.example.com/***", seen.delivery().get(0).url());
            assertEquals(Boolean.TRUE, seen.delivery().get(0).urlConfigured());
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
            assertEquals("https://hooks.example.com/***", rest.listRules(null, 0, 20).rules().get(0).delivery().get(0).url());
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

    @Nested
    @DisplayName("webhook URLs are secrets — never returned, for administrators either")
    class WebhookUrls {

        private static final String TOKEN = "T000/B000/s3cr3tT0k3n";
        private final DeliveryTarget secretHook = DeliveryTarget.webhook("https://hooks.example.com/services/" + TOKEN + "?x=1", "${vault:hook}");

        private void noTokenIn(Object response) throws Exception {
            String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
            assertFalse(json.contains("s3cr3tT0k3n"), json);
            assertFalse(json.contains("services"), json);
        }

        @Test
        @DisplayName("list, single read, create and update all answer the masked URL with urlConfigured")
        void everyReadIsMasked() throws Exception {
            asAdmin();
            AlertRule stored = scoped(AlertScope.agent(AGENT), secretHook);
            when(store.list(null, false, 0, 20)).thenReturn(List.of(stored));
            when(store.read(RULE_ID)).thenReturn(Optional.of(stored));

            noTokenIn(rest.listRules(null, 0, 20));
            AlertRule read = rest.readRule(RULE_ID);
            noTokenIn(read);
            assertEquals("https://hooks.example.com/***", read.delivery().get(0).url());
            assertEquals(Boolean.TRUE, read.delivery().get(0).urlConfigured());
            noTokenIn(rest.createRule(scoped(AlertScope.agent(AGENT), secretHook)).getEntity());
            noTokenIn(rest.updateRule(RULE_ID, scoped(AlertScope.agent(AGENT), secretHook)));
        }

        @Test
        @DisplayName("the stored rule keeps the full URL, and the read-only flag is never stored")
        void storedWhole() {
            asAdmin();
            DeliveryTarget withFlag = new DeliveryTarget(secretHook.type(), null, secretHook.url(), secretHook.signingSecret(), true);
            rest.createRule(scoped(AlertScope.agent(AGENT), withFlag));
            ArgumentCaptor<AlertRule> created = ArgumentCaptor.forClass(AlertRule.class);
            verify(store).create(created.capture());
            assertEquals(secretHook.url(), created.getValue().delivery().get(0).url());
            assertNull(created.getValue().delivery().get(0).urlConfigured());
        }

        @Test
        @DisplayName("an update that sends the masked value back, or none, under the target's id keeps the stored URL; a new value replaces it")
        void updateKeepsOrReplaces() {
            asAdmin();
            when(store.read(RULE_ID)).thenReturn(Optional.of(scoped(AlertScope.agent(AGENT), secretHook)));
            String id = rest.readRule(RULE_ID).delivery().get(0).targetId();
            ArgumentCaptor<AlertRule> updated = ArgumentCaptor.forClass(AlertRule.class);

            rest.updateRule(RULE_ID,
                    scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook("https://hooks.example.com/***", "${vault:hook}").withTargetId(id)));
            rest.updateRule(RULE_ID, scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook(null, "${vault:hook}").withTargetId(id)));
            rest.updateRule(RULE_ID,
                    scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook("https://other.example.com/new", "${vault:hook}").withTargetId(id)));

            verify(store, times(3)).update(updated.capture());
            assertEquals(secretHook.url(), updated.getAllValues().get(0).delivery().get(0).url());
            assertEquals(secretHook.url(), updated.getAllValues().get(1).delivery().get(0).url());
            assertEquals("https://other.example.com/new", updated.getAllValues().get(2).delivery().get(0).url());
            for (AlertRule saved : updated.getAllValues()) {
                assertEquals(id, saved.delivery().get(0).targetId(), "the id is kept across updates");
            }
        }

        private final DeliveryTarget hookA = DeliveryTarget.webhook("https://hooks.example.com/services/AAA/" + TOKEN, "${vault:a}")
                .withTargetId("tA");
        private final DeliveryTarget hookB = DeliveryTarget.webhook("https://hooks.example.com/services/BBB/other", "${vault:b}")
                .withTargetId("tB");

        private AlertRule twoSameHost() {
            AlertRule stored = scoped(AlertScope.agent(AGENT), hookA, hookB);
            when(store.read(RULE_ID)).thenReturn(Optional.of(stored));
            return stored;
        }

        private AlertRule savedAfter(AlertRule body) {
            rest.updateRule(RULE_ID, body);
            ArgumentCaptor<AlertRule> updated = ArgumentCaptor.forClass(AlertRule.class);
            verify(store).update(updated.capture());
            return updated.getValue();
        }

        @Test
        @DisplayName("two masked targets on one host: removing the first keeps the second's own URL, not the removed one's")
        void removeOneOfTwoSameHost() {
            asAdmin();
            twoSameHost();
            AlertRule read = rest.readRule(RULE_ID);
            DeliveryTarget second = read.delivery().get(1);
            assertEquals("tB", second.targetId());
            AlertRule saved = savedAfter(scoped(AlertScope.agent(AGENT), second));
            assertEquals(List.of(hookB.url()), saved.delivery().stream().map(DeliveryTarget::url).toList());
            assertEquals("tB", saved.delivery().get(0).targetId());
        }

        @Test
        @DisplayName("two masked targets on one host, reordered: each keeps its own URL")
        void reorder() {
            asAdmin();
            twoSameHost();
            AlertRule read = rest.readRule(RULE_ID);
            AlertRule saved = savedAfter(scoped(AlertScope.agent(AGENT), read.delivery().get(1), read.delivery().get(0)));
            assertEquals(List.of(hookB.url(), hookA.url()), saved.delivery().stream().map(DeliveryTarget::url).toList());
            assertEquals(List.of("tB", "tA"), saved.delivery().stream().map(DeliveryTarget::targetId).toList());
        }

        @Test
        @DisplayName("a new target gets an id from the server and must bring a real URL — a masked one without an id is refused")
        void newTarget() {
            asAdmin();
            twoSameHost();
            AlertRule read = rest.readRule(RULE_ID);
            DeliveryTarget fresh = DeliveryTarget.webhook("https://hooks.example.com/services/CCC/new", "${vault:c}");
            AlertRule saved = savedAfter(scoped(AlertScope.agent(AGENT), read.delivery().get(0), read.delivery().get(1), fresh));
            assertEquals(hookA.url(), saved.delivery().get(0).url());
            assertEquals(fresh.url(), saved.delivery().get(2).url());
            String freshId = saved.delivery().get(2).targetId();
            assertFalse(freshId == null || freshId.isBlank() || freshId.equals("tA") || freshId.equals("tB"));

            assertError(400, AlertErrors.INVALID_RULE, () -> rest.updateRule(RULE_ID,
                    scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook("https://hooks.example.com/***", "${vault:c}"))));
        }

        @Test
        @DisplayName("a masked or empty URL under an id the stored rule does not have is refused — never guessed by host or position")
        void unknownTargetIdRefused() {
            asAdmin();
            twoSameHost();
            assertError(400, AlertErrors.INVALID_RULE, () -> rest.updateRule(RULE_ID,
                    scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook("https://hooks.example.com/***", "${vault:a}").withTargetId("tZ"))));
            assertError(400, AlertErrors.INVALID_RULE, () -> rest.updateRule(RULE_ID,
                    scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook(null, "${vault:a}").withTargetId("tZ"))));
            verify(store, never()).update(any());
        }

        @Test
        @DisplayName("a client cannot choose ids: an unknown id on a real URL is replaced, and a create drops every id")
        void idsAreTheServers() {
            asAdmin();
            twoSameHost();
            AlertRule saved = savedAfter(scoped(AlertScope.agent(AGENT),
                    DeliveryTarget.webhook("https://hooks.example.com/services/DDD/x", "${vault:d}").withTargetId("tA-forged")));
            assertFalse("tA-forged".equals(saved.delivery().get(0).targetId()));

            rest.createRule(scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook("https://hooks.example.com/services/EEE/x", "${vault:e}")
                    .withTargetId("tA")));
            ArgumentCaptor<AlertRule> created = ArgumentCaptor.forClass(AlertRule.class);
            verify(store).create(created.capture());
            assertEquals(created.getValue().delivery().get(0).derivedTargetId(), created.getValue().delivery().get(0).targetId());
        }

        @Test
        @DisplayName("a rule stored before ids existed: reads show the same derived ids, and its first update restores by them")
        void legacyRuleWithoutIds() {
            asAdmin();
            DeliveryTarget legacyA = DeliveryTarget.webhook("https://hooks.example.com/services/AAA/" + TOKEN, "${vault:a}");
            DeliveryTarget legacyB = DeliveryTarget.webhook("https://hooks.example.com/services/BBB/other", "${vault:b}");
            when(store.read(RULE_ID)).thenReturn(Optional.of(scoped(AlertScope.agent(AGENT), legacyA, legacyB)));
            AlertRule first = rest.readRule(RULE_ID);
            AlertRule again = rest.readRule(RULE_ID);
            assertEquals(first.delivery().stream().map(DeliveryTarget::targetId).toList(),
                    again.delivery().stream().map(DeliveryTarget::targetId).toList(), "stable across reads");
            assertFalse(first.delivery().get(0).targetId().equals(first.delivery().get(1).targetId()));

            AlertRule saved = savedAfter(scoped(AlertScope.agent(AGENT), first.delivery().get(1)));
            assertEquals(legacyB.url(), saved.delivery().get(0).url());
            assertEquals(first.delivery().get(1).targetId(), saved.delivery().get(0).targetId(), "the derived id is stored from now on");
        }

        @Test
        @DisplayName("a masked URL of another host matches nothing and is refused, never stored masked")
        void unmatchedMaskIsRefused() {
            asAdmin();
            when(store.read(RULE_ID)).thenReturn(Optional.of(scoped(AlertScope.agent(AGENT), secretHook)));
            assertError(400, AlertErrors.INVALID_RULE,
                    () -> rest.updateRule(RULE_ID,
                            scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook("https://evil.example.com/***", "${vault:hook}"))));
            assertError(400, AlertErrors.INVALID_RULE,
                    () -> rest.createRule(scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook("https://hooks.example.com/***", "${vault:hook}"))));
            verify(store, never()).update(any());
            verify(store, never()).create(any());
        }

        @Test
        @DisplayName("a test send of a saved rule restores the stored URL for the sender, and echoes only the host")
        void testSendRestoresAndEchoesHost() throws Exception {
            asAdmin();
            when(store.read(RULE_ID)).thenReturn(Optional.of(scoped(AlertScope.agent(AGENT), secretHook)));
            String id = rest.readRule(RULE_ID).delivery().get(0).targetId();
            AlertRule edited = scoped(AlertScope.agent(AGENT),
                    DeliveryTarget.webhook("https://hooks.example.com/***", "${vault:hook}").withTargetId(id)).withId(RULE_ID);

            TestResult result = rest.testRule(edited);

            ArgumentCaptor<DeliveryTarget> sent = ArgumentCaptor.forClass(DeliveryTarget.class);
            verify(senders).send(any(), sent.capture());
            assertEquals(secretHook.url(), sent.getValue().url());
            noTokenIn(result);
            assertEquals("Webhook hooks.example.com", result.deliveries().get(0).label());

            // Without the id, the test send of a saved rule restores nothing and is
            // refused.
            AlertRule noId = scoped(AlertScope.agent(AGENT), DeliveryTarget.webhook("https://hooks.example.com/***", "${vault:hook}"))
                    .withId(RULE_ID);
            assertError(400, AlertErrors.INVALID_RULE, () -> rest.testRule(noId));
        }

        @Test
        @DisplayName("a test without a rule body is a 400, not a server error")
        void nullBody() {
            asAdmin();
            assertError(400, AlertErrors.INVALID_RULE, () -> rest.testRule(null));
        }

        @Test
        @DisplayName("a vault reference is accepted as the URL itself and shown as it is")
        void vaultUrl() {
            asAdmin();
            DeliveryTarget vaultHook = DeliveryTarget.webhook("${vault:alert-hook-url}", "${vault:hook}");
            AlertRule created = (AlertRule) rest.createRule(scoped(AlertScope.agent(AGENT), vaultHook)).getEntity();
            assertEquals("${vault:alert-hook-url}", created.delivery().get(0).url());
            assertEquals(Boolean.TRUE, created.delivery().get(0).urlConfigured());
        }
    }
}
