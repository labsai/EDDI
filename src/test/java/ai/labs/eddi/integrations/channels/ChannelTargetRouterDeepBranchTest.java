/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.LegacyTarget;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ChannelTargetRouter — Deep Branch Coverage")
@SuppressWarnings("unchecked")
class ChannelTargetRouterDeepBranchTest {

    private ICache<String, ChannelTarget> threadTargetLock;
    private ChannelTargetRouter router;

    @BeforeEach
    void setUp() throws Exception {
        var channelStore = mock(IChannelIntegrationStore.class);
        var descriptorStore = mock(IDocumentDescriptorStore.class);
        var agentAdmin = mock(IRestAgentAdministration.class);
        var agentStore = mock(IAgentStore.class);
        var secretResolver = mock(SecretResolver.class);
        var cacheFactory = mock(ICacheFactory.class);
        threadTargetLock = mock(ICache.class);
        doReturn(threadTargetLock).when(cacheFactory).getCache(anyString(), any(Duration.class));

        doReturn(List.of()).when(descriptorStore).readDescriptors(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        doReturn(List.of()).when(agentAdmin).getDeploymentStatuses(any());

        router = new ChannelTargetRouter(channelStore, descriptorStore, agentAdmin, agentStore, secretResolver, cacheFactory);
        // Prevent refresh from running during tests
        setField(router, "lastRefreshTime", Long.MAX_VALUE);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private ChannelIntegrationConfiguration createIntegration(String defaultTargetName, List<ChannelTarget> targets,
                                                              Map<String, String> platformConfig) {
        var integration = new ChannelIntegrationConfiguration();
        integration.setName("test-integration");
        integration.setChannelType("slack");
        integration.setDefaultTargetName(defaultTargetName);
        integration.setTargets(targets);
        integration.setPlatformConfig(platformConfig);
        return integration;
    }

    private ChannelTarget createTarget(String name, List<String> triggers) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setTriggers(triggers);
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId("agent1");
        return target;
    }

    // ─── resolveFromIntegration ──────────────────────────────────────────

    @Nested
    @DisplayName("resolveFromIntegration")
    class ResolveFromIntegration {

        @Test
        @DisplayName("null message → null")
        void nullMessage() {
            var integration = createIntegration("default", List.of(), Map.of());
            var result = router.resolveFromIntegration(integration, null);
            assertNull(result);
        }

        @Test
        @DisplayName("blank message → null")
        void blankMessage() {
            var integration = createIntegration("default", List.of(), Map.of());
            var result = router.resolveFromIntegration(integration, "   ");
            assertNull(result);
        }

        @Test
        @DisplayName("empty message → null")
        void emptyMessage() {
            var integration = createIntegration("default", List.of(), Map.of());
            var result = router.resolveFromIntegration(integration, "");
            assertNull(result);
        }

        @Test
        @DisplayName("'help' message → null")
        void helpMessage() {
            var integration = createIntegration("default", List.of(), Map.of());
            assertNull(router.resolveFromIntegration(integration, "help"));
        }

        @Test
        @DisplayName("'HELP' message → null")
        void helpUpperCase() {
            var integration = createIntegration("default", List.of(), Map.of());
            assertNull(router.resolveFromIntegration(integration, "HELP"));
        }

        @Test
        @DisplayName("colon with matching trigger → returns stripped message")
        void colonMatchingTrigger() {
            var target = createTarget("gpt4", List.of("gpt4", "gpt"));
            var defaultTarget = createTarget("default", List.of());
            var integration = createIntegration("default", List.of(target, defaultTarget), Map.of());

            var result = router.resolveFromIntegration(integration, "gpt4: what is AI?");
            assertNotNull(result);
            assertEquals("what is AI?", result.strippedMessage());
            assertEquals("gpt4", result.target().getName());
        }

        @Test
        @DisplayName("colon with no matching trigger → default target")
        void colonNoMatch() {
            var target = createTarget("gpt4", List.of("gpt4"));
            var defaultTarget = createTarget("default", List.of());
            var integration = createIntegration("default", List.of(target, defaultTarget), Map.of());

            var result = router.resolveFromIntegration(integration, "unknown: something");
            assertNotNull(result);
            assertEquals("default", result.target().getName());
            assertEquals("unknown: something", result.strippedMessage());
        }

        @Test
        @DisplayName("colon with null targets → default target")
        void colonNullTargets() {
            var defaultTarget = createTarget("default", List.of());
            var integration = createIntegration("default", null, Map.of());
            integration.setTargets(List.of(defaultTarget));

            // Re-create with the target but no triggers to match
            var result = router.resolveFromIntegration(integration, "key: value");
            assertNotNull(result);
        }

        @Test
        @DisplayName("colon, target has null triggers → skip that target")
        void targetNullTriggers() {
            var target = createTarget("gpt4", null);
            var defaultTarget = createTarget("default", List.of());
            var integration = createIntegration("default", List.of(target, defaultTarget), Map.of());

            var result = router.resolveFromIntegration(integration, "gpt4: test");
            assertNotNull(result);
            assertEquals("default", result.target().getName());
        }

        @Test
        @DisplayName("colon, trigger is null in list → skip that trigger")
        void nullTriggerInList() {
            var triggerList = new ArrayList<String>();
            triggerList.add(null);
            triggerList.add("gpt4");
            var target = createTarget("gpt4", triggerList);
            var defaultTarget = createTarget("default", List.of());
            var integration = createIntegration("default", List.of(target, defaultTarget), Map.of());

            var result = router.resolveFromIntegration(integration, "gpt4: test");
            assertNotNull(result);
            assertEquals("gpt4", result.target().getName());
        }

        @Test
        @DisplayName("no colon in message → default target with full message")
        void noColon() {
            var defaultTarget = createTarget("default", List.of());
            var integration = createIntegration("default", List.of(defaultTarget), Map.of());

            var result = router.resolveFromIntegration(integration, "hello world");
            assertNotNull(result);
            assertEquals("hello world", result.strippedMessage());
            assertEquals("default", result.target().getName());
        }

        @Test
        @DisplayName("no default target — defaultTargetName null → null")
        void noDefaultTargetNull() {
            var target = createTarget("gpt4", List.of("gpt4"));
            var integration = createIntegration(null, List.of(target), Map.of());

            var result = router.resolveFromIntegration(integration, "hello");
            assertNull(result);
        }

        @Test
        @DisplayName("no default target — targets null → null")
        void noDefaultTargetTargetsNull() {
            var integration = createIntegration("default", null, Map.of());

            var result = router.resolveFromIntegration(integration, "hello");
            assertNull(result);
        }

        @Test
        @DisplayName("no default target — no name match → null with warning")
        void noDefaultTargetNameMismatch() {
            var target = createTarget("gpt4", List.of("gpt4"));
            var integration = createIntegration("nonexistent", List.of(target), Map.of());

            var result = router.resolveFromIntegration(integration, "hello");
            assertNull(result);
        }
    }

    // ─── resolveTarget with legacy fallback ─────────────────────────────

    @Nested
    @DisplayName("resolveTarget — legacy fallback")
    class ResolveTargetLegacy {

        @Test
        @DisplayName("Slack legacy target — returns resolved")
        void legacyReturns() throws Exception {
            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            var result = router.resolveTarget("slack", "C123", "hello");
            assertNotNull(result);
            assertEquals("hello", result.strippedMessage());
        }

        @Test
        @DisplayName("Slack legacy target with empty message → null")
        void legacyEmptyMessage() throws Exception {
            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            assertNull(router.resolveTarget("slack", "C123", ""));
        }

        @Test
        @DisplayName("Slack legacy target with null message → null")
        void legacyNullMessage() throws Exception {
            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            assertNull(router.resolveTarget("slack", "C123", null));
        }

        @Test
        @DisplayName("Slack legacy target with 'help' → null")
        void legacyHelpMessage() throws Exception {
            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            assertNull(router.resolveTarget("slack", "C123", "help"));
        }

        @Test
        @DisplayName("non-Slack channel — no legacy fallback")
        void nonSlackNoFallback() throws Exception {
            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            assertNull(router.resolveTarget("teams", "C123", "hello"));
        }
    }

    // ─── resolveDefaultForDm ────────────────────────────────────────────

    @Nested
    @DisplayName("resolveDefaultForDm — with data")
    class ResolveDefaultForDmWithData {

        @Test
        @DisplayName("matching integration → resolves")
        void matchingIntegration() throws Exception {
            var defaultTarget = createTarget("default", List.of());
            var integration = createIntegration("default", List.of(defaultTarget), Map.of("channelId", "C123"));
            setField(router, "integrationMap", Map.of("slack:C123", integration));

            var result = router.resolveDefaultForDm("slack", "hello there");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Slack legacy fallback with valid message → returns")
        void legacyFallbackValid() throws Exception {
            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            var result = router.resolveDefaultForDm("slack", "hello");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Slack legacy fallback with blank → null")
        void legacyFallbackBlank() throws Exception {
            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            assertNull(router.resolveDefaultForDm("slack", "  "));
        }

        @Test
        @DisplayName("Slack legacy fallback with 'help' → null")
        void legacyFallbackHelp() throws Exception {
            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            assertNull(router.resolveDefaultForDm("slack", "help"));
        }
    }

    // ─── resolveThreadTarget with legacy credentials ────────────────────

    @Nested
    @DisplayName("resolveThreadTarget — legacy credentials")
    class ThreadTargetLegacy {

        @Test
        @DisplayName("Slack, no integration, has legacy → attaches legacy credentials")
        void slackLegacyCreds() throws Exception {
            var channelTarget = new ChannelTarget();
            channelTarget.setName("test");
            doReturn(channelTarget).when(threadTargetLock).get("slack:C123:ts1");

            var legacyMap = Map.of("C123", new LegacyTarget("agent1", "bot-token", "sign-secret", null));
            setField(router, "legacyMap", legacyMap);

            var result = router.resolveThreadTarget("slack", "C123", "ts1");
            assertNotNull(result);
            assertEquals("bot-token", result.legacyBotToken());
            assertEquals("sign-secret", result.legacySigningSecret());
        }

        @Test
        @DisplayName("non-Slack, no integration → no legacy credentials")
        void nonSlackNoCreds() throws Exception {
            var channelTarget = new ChannelTarget();
            channelTarget.setName("test");
            doReturn(channelTarget).when(threadTargetLock).get("teams:C123:ts1");

            var result = router.resolveThreadTarget("teams", "C123", "ts1");
            assertNotNull(result);
            assertNull(result.legacyBotToken());
            assertNull(result.legacySigningSecret());
        }
    }

    // ─── Routes bound to the verifying app ──────────────────────────────

    @Nested
    @DisplayName("routes bound to the app whose secret verified the event")
    class SignerBoundRoutes {

        private ChannelTarget agentTarget(String agentId) {
            var target = createTarget(agentId, List.of());
            target.setTargetId(agentId);
            return target;
        }

        private ChannelIntegrationConfiguration integrationWith(String name, String secret, ChannelTarget target) {
            var integration = createIntegration(target.getName(), List.of(target),
                    Map.of("channelId", "C_" + name, "signingSecret", secret, "botToken", "xoxb-" + name));
            integration.setName(name);
            return integration;
        }

        @Test
        @DisplayName("DM thread credentials come only from an app holding the secret AND serving the locked target")
        void threadCredentialsRequireSecretAndTarget() throws Exception {
            var integrationA = integrationWith("int-a", "secret-a", agentTarget("agent-A"));
            var integrationB = integrationWith("int-b", "secret-b", agentTarget("agent-B"));
            setField(router, "integrationMap", Map.of("slack:C_int-a", integrationA, "slack:C_int-b", integrationB));

            var own = router.threadCredentialsForDm("slack", agentTarget("agent-A"), "secret-a");
            assertNotNull(own);
            assertSame(integrationA, own.integration());
            assertEquals("xoxb-int-a", own.botToken());

            // B's secret cannot continue a thread locked to A's agent.
            assertNull(router.threadCredentialsForDm("slack", agentTarget("agent-A"), "secret-b"));
            assertNull(router.threadCredentialsForDm("slack", agentTarget("agent-A"), null));
        }

        @Test
        @DisplayName("DM thread credentials of a legacy connector: same secret and same target")
        void threadCredentialsFromLegacyConnector() throws Exception {
            setField(router, "legacyMap", Map.of("C_L", new LegacyTarget("agent-L", "xoxb-l", "secret-l", null)));

            var own = router.threadCredentialsForDm("slack", agentTarget("agent-L"), "secret-l");
            assertNotNull(own);
            assertEquals("xoxb-l", own.botToken());
            assertEquals("secret-l", own.signingSecret());
            assertNull(router.threadCredentialsForDm("slack", agentTarget("agent-L"), "secret-x"));
            assertNull(router.threadCredentialsForDm("slack", agentTarget("agent-Z"), "secret-l"));
        }

        @Test
        @DisplayName("a legacy connector's DM goes to the connector holding the secret, never to an integration")
        void legacyDmGoesToItsConnector() throws Exception {
            var integration = integrationWith("int-a", "secret-a", agentTarget("agent-A"));
            setField(router, "integrationMap", Map.of("slack:C_int-a", integration));
            setField(router, "legacyMap", Map.of(
                    "C_L1", new LegacyTarget("agent-L1", "xoxb-l1", "secret-l1", null),
                    "C_L2", new LegacyTarget("agent-L2", "xoxb-l2", "secret-l2", null)));

            var resolved = router.resolveLegacyDefaultForDm("hello", "secret-l2");
            assertNotNull(resolved);
            assertEquals("agent-L2", resolved.target().getTargetId());
            assertEquals("xoxb-l2", resolved.botToken());
            assertNull(router.resolveLegacyDefaultForDm("hello", "secret-a"), "an integration's secret selects no legacy DM");
            assertNull(router.resolveLegacyDefaultForDm("help", "secret-l2"));
        }

        @Test
        @DisplayName("secretsEqual: equal non-blank secrets only")
        void secretsEqual() {
            assertTrue(ChannelTargetRouter.secretsEqual("s", "s"));
            assertFalse(ChannelTargetRouter.secretsEqual("s", "t"));
            assertFalse(ChannelTargetRouter.secretsEqual(null, null));
            assertFalse(ChannelTargetRouter.secretsEqual(" ", " "));
        }
    }

    // ─── getBotToken ────────────────────────────────────────────────────

    @Nested
    @DisplayName("getBotToken")
    class GetBotToken {

        @Test
        @DisplayName("integration with botToken → returns token")
        void integrationBotToken() throws Exception {
            var integration = createIntegration("default", List.of(), Map.of("botToken", "xoxb-123", "channelId", "C1"));
            setField(router, "integrationMap", Map.of("slack:C1", integration));

            assertEquals("xoxb-123", router.getBotToken("slack", "C1"));
        }

        @Test
        @DisplayName("integration with blank botToken → falls to legacy")
        void integrationBlankToken() throws Exception {
            var integration = createIntegration("default", List.of(), Map.of("botToken", "   ", "channelId", "C1"));
            setField(router, "integrationMap", Map.of("slack:C1", integration));

            var legacyMap = Map.of("C1", new LegacyTarget("agent1", "legacy-token", null, null));
            setField(router, "legacyMap", legacyMap);

            assertEquals("legacy-token", router.getBotToken("slack", "C1"));
        }

        @Test
        @DisplayName("integration with null platformConfig → falls to legacy")
        void integrationNullPlatformConfig() throws Exception {
            var integration = createIntegration("default", List.of(), null);
            // Need a working integrationMap key
            setField(router, "integrationMap", Map.of("slack:C1", integration));

            var legacyMap = Map.of("C1", new LegacyTarget("agent1", "legacy-token", null, null));
            setField(router, "legacyMap", legacyMap);

            assertEquals("legacy-token", router.getBotToken("slack", "C1"));
        }

        @Test
        @DisplayName("no integration, Slack, legacy has null token → null")
        void legacyNullToken() throws Exception {
            var legacyMap = Map.of("C1", new LegacyTarget("agent1", null, null, null));
            setField(router, "legacyMap", legacyMap);

            assertNull(router.getBotToken("slack", "C1"));
        }

        @Test
        @DisplayName("no integration, non-Slack → null")
        void nonSlackNull() {
            assertNull(router.getBotToken("teams", "C1"));
        }
    }

    // ─── hasAnyChannels ─────────────────────────────────────────────────

    @Nested
    @DisplayName("hasAnyChannels")
    class HasAnyChannels {

        @Test
        @DisplayName("Slack with integration → true")
        void slackWithIntegration() throws Exception {
            var integration = createIntegration("default", List.of(), Map.of("channelId", "C1"));
            setField(router, "integrationMap", Map.of("slack:C1", integration));

            assertTrue(router.hasAnyChannels("slack"));
        }

        @Test
        @DisplayName("Slack without integration but with legacy → true")
        void slackWithLegacy() throws Exception {
            var legacyMap = Map.of("C1", new LegacyTarget("agent1", "token", "secret", null));
            setField(router, "legacyMap", legacyMap);

            assertTrue(router.hasAnyChannels("slack"));
        }

        @Test
        @DisplayName("Slack with neither → false")
        void slackWithNeither() {
            assertFalse(router.hasAnyChannels("slack"));
        }

        @Test
        @DisplayName("non-Slack with integration → true")
        void nonSlackWithIntegration() throws Exception {
            var integration = createIntegration("default", List.of(), Map.of("channelId", "C1"));
            integration.setChannelType("teams");
            setField(router, "integrationMap", Map.of("teams:C1", integration));

            assertTrue(router.hasAnyChannels("teams"));
        }

        @Test
        @DisplayName("non-Slack without integration → false")
        void nonSlackWithout() {
            assertFalse(router.hasAnyChannels("teams"));
        }

        @Test
        @DisplayName("null channel type → false")
        void nullType() {
            assertFalse(router.hasAnyChannels(null));
        }
    }

    // ─── getIntegrationByName — ambiguity refusal (Finding C) ─────────────

    @Nested
    @DisplayName("getIntegrationByName — non-unique display name")
    class GetIntegrationByNameAmbiguity {

        private ChannelIntegrationConfiguration named(String channelId, String name, String secret) {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setName(name);
            cfg.setChannelType("slack");
            var pc = new HashMap<String, String>();
            pc.put("channelId", channelId);
            pc.put("signingSecret", secret);
            cfg.setPlatformConfig(pc);
            return cfg;
        }

        @Test
        @DisplayName("unique name resolves")
        void uniqueNameResolves() throws Exception {
            setField(router, "integrationMap", Map.of(
                    "slack:C_VICTIM", named("C_VICTIM", "Support Hub", "victim-secret")));
            var resolved = router.getIntegrationByName("slack", "Support Hub");
            assertTrue(resolved.isPresent());
            assertEquals("victim-secret", resolved.get().getPlatformConfig().get("signingSecret"));
        }

        @Test
        @DisplayName("two integrations sharing a name → refuse (empty), so an attacker "
                + "cannot bind a decision to the victim by copying its name")
        void duplicateNameRefused() throws Exception {
            // Victim integration and an attacker's integration (different channel, its own
            // secret) both named "Support Hub".
            setField(router, "integrationMap", new LinkedHashMap<>(Map.of(
                    "slack:C_VICTIM", named("C_VICTIM", "Support Hub", "victim-secret"),
                    "slack:C_ATTACKER", named("C_ATTACKER", "Support Hub", "attacker-secret"))));

            var resolved = router.getIntegrationByName("slack", "Support Hub");

            assertTrue(resolved.isEmpty(),
                    "an ambiguous name must resolve to no integration (fail-closed), never to either one");
        }
    }

    // ─── getSigningSecrets with data ─────────────────────────────────────

    @Nested
    @DisplayName("getSigningSecrets — with data")
    class GetSigningSecretsWithData {

        @Test
        @DisplayName("Slack signing secrets returned")
        void slackSecrets() throws Exception {
            setField(router, "signingSecretsByType", Map.of("slack", Set.of("sec1", "sec2")));
            var secrets = router.getSigningSecrets("SLACK");
            assertEquals(2, secrets.size());
            assertTrue(secrets.contains("sec1"));
        }
    }
}
