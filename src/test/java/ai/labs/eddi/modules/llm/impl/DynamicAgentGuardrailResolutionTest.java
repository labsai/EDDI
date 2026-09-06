/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a group's {@link DynamicAgentConfig} is resolved for a member turn — the
 * guardrail that decides whether an LLM may create, recruit or delegate to
 * agents.
 * <p>
 * The defect these pin: the resolver accepted only a <em>typed</em>
 * {@code DynamicAgentConfig} out of the context value. A {@link Context} whose
 * value round-trips through the conversation store comes back as a raw
 * {@code LinkedHashMap} ({@code ConversationMemoryStore} rebuilds it as
 * {@code new Context(type, map.get("value"))}), so any turn running against a
 * RELOADED step missed the type check and fell through to the fully-permissive
 * standalone default — creation, recruitment and delegation all ON for a group
 * that may have disabled every one of them.
 * <p>
 * The reachable trigger is ordinary: a member's gated tool call inside a group
 * is auto-rejected by {@code MemberTurnExecutor#tryResolveMemberToolPause},
 * which resumes the member conversation, and {@code Conversation#resume}
 * re-enters the same LlmTask at the same index against memory freshly loaded
 * from the store.
 *
 * @author ginccc
 */
@DisplayName("DynamicAgentToolsProvider — guardrail resolution")
class DynamicAgentGuardrailResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ConversationMemory memory() {
        return new ConversationMemory("conv-a", "agent-a", 1, "user-1");
    }

    private static void putPolicy(ConversationMemory memory, Object value) {
        memory.getCurrentStep().storeData(
                new Data<>(DynamicAgentToolsProvider.CONTEXT_DYNAMIC_AGENT_CONFIG,
                        new Context(Context.ContextType.object, value)));
    }

    /** A group policy with every capability switched off. */
    private static DynamicAgentConfig lockedDown() {
        var config = new DynamicAgentConfig();
        config.setEnabled(false);
        config.setAllowCreation(false);
        config.setAllowRecruitment(false);
        config.setAllowDelegation(false);
        return config;
    }

    /** The same policy as it comes back from the store: a plain map. */
    private static Map<String, Object> asStoredMap(DynamicAgentConfig config) {
        return MAPPER.convertValue(config, new TypeReference<>() {
        });
    }

    @Nested
    @DisplayName("a stored (map-shaped) group policy")
    class StoredPolicy {

        @Test
        @DisplayName("is honoured, not replaced by the permissive default")
        void storedPolicyIsHonoured() {
            var memory = memory();
            putPolicy(memory, asStoredMap(lockedDown()));

            var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertFalse(resolved.isEnabled(), "a disabled group policy must survive the store round-trip");
            assertFalse(resolved.isAllowCreation());
            assertFalse(resolved.isAllowRecruitment());
            assertFalse(resolved.isAllowDelegation());
        }

        @Test
        @DisplayName("keeps the capabilities it does grant")
        void storedPolicyKeepsWhatItGrants() {
            var config = new DynamicAgentConfig();
            config.setEnabled(true);
            config.setAllowCreation(false);
            config.setAllowRecruitment(true);
            config.setAllowDelegation(false);

            var memory = memory();
            putPolicy(memory, asStoredMap(config));

            var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertTrue(resolved.isEnabled());
            assertFalse(resolved.isAllowCreation(), "conversion must not flip a false to the default true");
            assertTrue(resolved.isAllowRecruitment());
            assertFalse(resolved.isAllowDelegation());
        }

        @Test
        @DisplayName("an explicitly null flag stays off — it is not dropped into the permissive POJO default")
        void explicitNullMustNotWidenThePolicy() {
            // The mapper is built from the shared SerializationCustomizer recipe for
            // its FAIL_ON_UNKNOWN_PROPERTIES=false. That recipe also sets NON_NULL as
            // the default property inclusion, and Jackson makes a bare Include a
            // CONTENT inclusion too — which filters map entries during the intermediate
            // serialization convertValue performs. Left alone, a stored
            // "allowDelegation": null never reaches the setter and the primitive keeps
            // its POJO default of true, so a fail-closed resolver would hand the turn a
            // capability the stored document did not grant.
            var stored = new LinkedHashMap<String, Object>();
            stored.put("enabled", true);
            stored.put("allowDelegation", null);
            stored.put("inheritParentModel", null);

            var memory = memory();
            putPolicy(memory, stored);

            var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertTrue(resolved.isEnabled(), "the readable part of the policy must still apply");
            assertFalse(resolved.isAllowDelegation(), "a stored null must read as false, not as the POJO default true");
            assertFalse(resolved.isInheritParentModel(), "a stored null must read as false, not as the POJO default true");
        }
    }

    @Nested
    @DisplayName("an unreadable group policy")
    class UnreadablePolicy {

        @Test
        @DisplayName("fails closed rather than falling back to the permissive default")
        void unreadablePolicyFailsClosed() {
            var memory = memory();
            putPolicy(memory, "not-a-config");

            var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertFalse(resolved.isEnabled(), "'the operator said something we cannot parse' must never mean 'yes to everything'");
            assertFalse(resolved.isAllowCreation());
            assertFalse(resolved.isAllowRecruitment());
            assertFalse(resolved.isAllowDelegation());
        }

        @Test
        @DisplayName("a map whose values do not fit the model fails closed")
        void unconvertibleMapFailsClosed() {
            var memory = memory();
            var bogus = new LinkedHashMap<String, Object>();
            // A genuine type mismatch: allowedModels is a Map, not a String. This is
            // what "we cannot read the operator's policy" actually looks like.
            bogus.put("allowedModels", "not-a-map");
            putPolicy(memory, bogus);

            var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertFalse(resolved.isEnabled());
            assertFalse(resolved.isAllowCreation());
        }

        @Test
        @DisplayName("an unknown key is ignored — a policy from another version still applies")
        void unknownKeyDoesNotDisableTheStoredPolicy() {
            // This used to fail closed, and that was the bug: the mapper here was the
            // one strict ObjectMapper in the codebase pointed at a stored config POJO.
            // Dropping any field from DynamicAgentConfig — which this project treats as
            // a safe change — would have turned every group conversation whose context
            // map predated the removal into "dynamic agents silently off", with only a
            // WARN. The same on a rolling upgrade, in either direction.
            var config = new DynamicAgentConfig();
            config.setEnabled(true);
            config.setAllowCreation(true);
            config.setAllowRecruitment(false);
            config.setMaxCreatedAgentsPerDiscussion(7);

            var stored = new LinkedHashMap<String, Object>(asStoredMap(config));
            stored.put("aFieldThisVersionNoLongerDeclares", 42);

            var memory = memory();
            putPolicy(memory, stored);

            var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertTrue(resolved.isEnabled(), "a retired key must not cost the discussion its dynamic-agent policy");
            assertTrue(resolved.isAllowCreation());
            assertFalse(resolved.isAllowRecruitment(), "the stored policy must survive intact, not be re-defaulted");
            assertEquals(7, resolved.getMaxCreatedAgentsPerDiscussion());
        }
    }

    @Nested
    @DisplayName("a typed group policy")
    class TypedPolicy {

        @Test
        @DisplayName("is returned as-is (the same-turn path is unchanged)")
        void typedPolicyIsReturnedAsIs() {
            var memory = memory();
            putPolicy(memory, lockedDown());

            var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertFalse(resolved.isEnabled());
            assertFalse(resolved.isAllowCreation());
        }
    }

    @Nested
    @DisplayName("no group policy at all")
    class NoPolicy {

        @Test
        @DisplayName("resolves to the permissive standalone default")
        void standaloneGetsThePermissiveDefault() {
            var resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory());

            assertTrue(resolved.isEnabled(), "a standalone agent whose operator whitelisted these tools opted in deliberately");
            assertTrue(resolved.isAllowCreation());
            assertTrue(resolved.isAllowRecruitment());
            assertTrue(resolved.isAllowDelegation());
        }

        @Test
        @DisplayName("a null context value counts as no policy")
        void nullContextValueIsNoPolicy() {
            var memory = memory();
            putPolicy(memory, null);

            assertTrue(DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory).isEnabled());
            assertFalse(DynamicAgentToolsProvider.hasGroupPolicy(memory));
        }
    }

    @Nested
    @DisplayName("hasGroupPolicy")
    class HasGroupPolicy {

        @Test
        @DisplayName("true for a typed policy, a stored map, and even an unreadable one")
        void presenceNotReadability() {
            var typed = memory();
            putPolicy(typed, lockedDown());
            assertTrue(DynamicAgentToolsProvider.hasGroupPolicy(typed));

            var stored = memory();
            putPolicy(stored, asStoredMap(lockedDown()));
            assertTrue(DynamicAgentToolsProvider.hasGroupPolicy(stored));

            var unreadable = memory();
            putPolicy(unreadable, "not-a-config");
            assertTrue(DynamicAgentToolsProvider.hasGroupPolicy(unreadable),
                    "an unreadable policy still means a group is in charge — treating it as 'no group' would "
                            + "hand the turn back to the permissive standalone path");
        }

        @Test
        @DisplayName("false for a standalone conversation")
        void falseForStandalone() {
            assertFalse(DynamicAgentToolsProvider.hasGroupPolicy(memory()));
        }
    }
}
