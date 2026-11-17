/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.HealthConfig;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Business references — config validation, capture and stored forms (H5)")
class HealthRefsTest {

    static final HealthRefHasher HASHER = HealthRefHasher.withSalt("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    static AgentConfiguration agent(List<String> refs, boolean hash, Map<String, String> events) {
        HealthConfig health = new HealthConfig();
        health.setExternalRefs(refs);
        health.setHashRefs(hash);
        health.setOutcomeEvents(events);
        AgentConfiguration configuration = new AgentConfiguration();
        configuration.setHealthConfig(health);
        return configuration;
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = {"email", "customerEmail", "e_mail", "phone_no", "mobileNumber", "ssn", "iban", "passport", "homeAddress",
                "first_name", "lastName", "dateOfBirth", "zip_code", "creditCard"})
        @DisplayName("identifier-like names are refused without hashRefs")
        void identifiersRefused(String name) {
            BadRequestException e = assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(List.of(name), false, null)));
            assertTrue(e.getMessage().contains("personal identifier"), e.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"email", "customerEmail", "iban"})
        @DisplayName("…and allowed with hashRefs")
        void identifiersAllowedHashed(String name) {
            assertDoesNotThrow(() -> HealthConfigValidator.validate(agent(List.of(name), true, null)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"orderId", "ticketId", "order.id", "caseNumber", "addressable", "emailed_count", "telemetryId", "telecomId"})
        @DisplayName("business object names pass — identifier words match whole words only")
        void businessNamesPass(String name) {
            // addressable / emailed / telemetry / telecom contain an identifier word, but
            // not as a whole word.
            assertDoesNotThrow(() -> HealthConfigValidator.validate(agent(List.of(name), false, null)));
        }

        @Test
        @DisplayName("bad names, duplicates and too many refs are refused")
        void badRefs() {
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(List.of("1order"), false, null)));
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(List.of("order id"), false, null)));
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(List.of("orderId", "orderId"), false, null)));
            List<String> many = new ArrayList<>();
            for (int i = 0; i <= HealthConfigValidator.MAX_EXTERNAL_REFS; i++) {
                many.add("ref" + i);
            }
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(many, false, null)));
            List<String> nulls = new ArrayList<>();
            nulls.add(null);
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(nulls, false, null)));
        }

        @Test
        @DisplayName("outcomeEvents map to a declarable outcome or NONE; types are checked")
        void outcomeEvents() {
            assertDoesNotThrow(() -> HealthConfigValidator.validate(agent(List.of(), false,
                    Map.of("order.placed", "RESOLVED", "ticket:reopened", "FAILED", "order/refunded", "NONE"))));
            Map<String, String> withNull = new LinkedHashMap<>();
            withNull.put("order.viewed", null);
            assertDoesNotThrow(() -> HealthConfigValidator.validate(agent(List.of(), false, withNull)));
            assertThrows(BadRequestException.class,
                    () -> HealthConfigValidator.validate(agent(List.of(), false, Map.of("order.placed", "ASSUMED_RESOLVED"))));
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(List.of(), false, Map.of("bad type", "RESOLVED"))));
            Map<String, String> many = new LinkedHashMap<>();
            for (int i = 0; i <= HealthConfigValidator.MAX_OUTCOME_EVENTS; i++) {
                many.put("e" + i, "NONE");
            }
            assertThrows(BadRequestException.class, () -> HealthConfigValidator.validate(agent(List.of(), false, many)));
        }

        @Test
        @DisplayName("refsNotSet names the refs no property setter writes — a warning list, never a refusal")
        void refsNotSet() {
            assertEquals(List.of("ticketId"),
                    HealthConfigValidator.refsNotSet(agent(List.of("orderId", "ticketId"), false, null), Set.of("orderId")));
            assertTrue(HealthConfigValidator.refsNotSet(agent(List.of(), false, null), Set.of()).isEmpty());
            assertTrue(HealthConfigValidator.refsNotSet(null, Set.of()).isEmpty());
        }

        @Test
        @DisplayName("the lenient read drops what validation would refuse; NONE and unknown values count without an outcome")
        void lenientRead() {
            Map<String, String> events = new LinkedHashMap<>();
            events.put("order.placed", "RESOLVED");
            events.put("order.viewed", "BOGUS");
            events.put("bad type", "RESOLVED");
            Resolved r = Resolved.of(agent(List.of("orderId", "email", "1x"), false, events).getHealthConfig());
            assertEquals(List.of("orderId"), r.externalRefs());
            assertEquals(Set.of("order.placed", "order.viewed"), r.outcomeEvents().keySet());
            assertEquals("RESOLVED", r.outcomeOfEvent("order.placed"));
            assertNull(r.outcomeOfEvent("order.viewed"));
            assertNull(r.outcomeOfEvent("unknown"));
            assertEquals(List.of("orderId", "email"), Resolved.of(agent(List.of("orderId", "email"), true, null).getHealthConfig()).externalRefs());
        }
    }

    @Nested
    @DisplayName("capture and stored forms")
    class Capture {

        private IConversationMemory memory(Property... properties) {
            ConversationProperties props = new ConversationProperties(null);
            for (Property p : properties) {
                props.put(p.getName(), p);
            }
            IConversationMemory memory = mock(IConversationMemory.class);
            when(memory.getConversationProperties()).thenReturn(props);
            return memory;
        }

        @Test
        @DisplayName("raw values: scalars of the named properties only, never a secret, too long or blank value")
        void rawValues() {
            Property secret = new Property("secretRef", "s3cr3t", Property.Scope.secret);
            Property vaulted = new Property("vaultedRef", "v", Property.Scope.conversation);
            vaulted.setAutoVaulted(true);
            Property number = new Property("ticketId", 42, Property.Scope.conversation);
            IConversationMemory memory = memory(new Property("orderId", "A-123", Property.Scope.conversation), secret, vaulted, number,
                    new Property("tooLong", "x".repeat(HealthRefs.MAX_VALUE_LENGTH + 1), Property.Scope.conversation),
                    new Property("other", "not a ref", Property.Scope.conversation));
            Map<String, String> raw = HealthRefs.rawValues(memory, List.of("orderId", "secretRef", "vaultedRef", "ticketId", "tooLong", "missing"));
            assertEquals(Map.of("orderId", "A-123", "ticketId", "42"), raw);
            assertTrue(HealthRefs.rawValues(memory, List.of()).isEmpty());
        }

        @Test
        @DisplayName("plain refs are name=value; hashed refs are name#hmac — deterministic, never the value")
        void storedForms() {
            Map<String, String> raw = new LinkedHashMap<>(Map.of("orderId", "A-123"));
            assertEquals(List.of("orderId=A-123"), HealthRefs.stored(raw, false, HASHER));
            List<String> hashed = HealthRefs.stored(raw, true, HASHER);
            assertEquals(1, hashed.size());
            assertTrue(hashed.get(0).startsWith("orderId#"));
            assertFalse(hashed.get(0).contains("A-123"));
            assertEquals(hashed, HealthRefs.stored(raw, true, HASHER), "deterministic");
            assertNotEquals(hashed, HealthRefs.stored(raw, true, HealthRefHasher.withSalt(new byte[32])), "salted");
            assertEquals("orderId", HealthRefs.nameOf(hashed.get(0)));
            assertTrue(HealthRefs.isHashed(hashed.get(0)));
            assertFalse(HealthRefs.isHashed("orderId=a#b"));
            assertEquals("a#b", HealthRefs.valueOf("orderId=a#b"));
        }

        @Test
        @DisplayName("without a salt a hashed ref is not recorded at all — never in plain")
        void noSaltNoRef() {
            assertTrue(HealthRefs.stored(Map.of("orderId", "A-1"), true, HealthRefHasher.withSalt(null)).isEmpty());
            assertTrue(HealthRefs.stored(Map.of("orderId", "A-1"), true, null).isEmpty());
        }

        @Test
        @DisplayName("match keys cover both forms, so a plain event finds refs recorded hashed")
        void matchKeys() {
            Set<String> keys = HealthRefs.matchKeys("orderId", "A-1", HASHER);
            assertEquals(2, keys.size());
            assertTrue(keys.contains("orderId=A-1"));
            assertTrue(keys.containsAll(HealthRefs.stored(Map.of("orderId", "A-1"), true, HASHER)));
        }

        @Test
        @DisplayName("a session's refs are the union of its turns', capped")
        void union() {
            assertEquals(List.of("a=1", "b=2"), HealthRefs.union(List.of(List.of("a=1"), List.of("a=1", "b=2"))));
            List<List<String>> many = new ArrayList<>();
            for (int i = 0; i < HealthRefs.MAX_SESSION_REFS + 5; i++) {
                many.add(List.of("r=" + i));
            }
            assertEquals(HealthRefs.MAX_SESSION_REFS, HealthRefs.union(many).size());
        }
    }
}
