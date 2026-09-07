/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.model;

import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the wire name of a behavior group's rule list.
 *
 * <p>
 * The accessors are {@code getRules}/{@code setRules}, so Jackson used to
 * serialise the list as {@code rules} while everything a human writes says
 * {@code behaviorRules} — the shipped reference configuration, the ZIP import
 * fixtures, the documentation, and the Manager's rules editor, which types the
 * field that way. Writes worked either way thanks to the alias, so the mismatch
 * surfaced only on reads: a rule set posted as {@code behaviorRules} came back
 * as {@code rules}, and the Manager rendered every group as "No rules in this
 * group" regardless of what it contained. The Manager's own MSW mocks returned
 * {@code behaviorRules}, so its test suite agreed with the fiction rather than
 * with the server.
 * </p>
 *
 * <p>
 * These tests are the contract: {@code behaviorRules} out, both names in.
 * </p>
 */
@DisplayName("RuleGroupConfiguration JSON shape")
class RuleGroupConfigurationJsonTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }

    private static RuleSetConfiguration oneGroupWithOneRule() {
        var rule = new RuleConfiguration();
        rule.setName("Welcome");
        rule.getActions().add("welcome_action");

        var group = new RuleGroupConfiguration();
        group.setName("Greetings");
        group.setRules(List.of(rule));

        var ruleSet = new RuleSetConfiguration();
        ruleSet.getBehaviorGroups().add(group);
        return ruleSet;
    }

    @Test
    @DisplayName("serialises the rule list as behaviorRules")
    void serialisesAsBehaviorRules() throws Exception {
        String json = mapper.writeValueAsString(oneGroupWithOneRule());

        assertTrue(json.contains("\"behaviorRules\""), "expected behaviorRules in: " + json);
        assertFalse(json.contains("\"rules\""), "the old spelling must not be emitted: " + json);
    }

    @Test
    @DisplayName("accepts behaviorRules on the way in")
    void acceptsBehaviorRules() throws Exception {
        var parsed = mapper.readValue("""
                { "behaviorGroups": [ { "name": "Greetings",
                    "behaviorRules": [ { "name": "Welcome", "actions": ["welcome_action"] } ] } ] }
                """, RuleSetConfiguration.class);

        assertEquals("Welcome", parsed.getBehaviorGroups().getFirst().getRules().getFirst().getName());
    }

    @Test
    @DisplayName("still accepts rules, so documents written before the rename keep loading")
    void stillAcceptsTheOldSpelling() throws Exception {
        var parsed = mapper.readValue("""
                { "behaviorGroups": [ { "name": "Greetings",
                    "rules": [ { "name": "Welcome", "actions": ["welcome_action"] } ] } ] }
                """, RuleSetConfiguration.class);

        assertEquals("Welcome", parsed.getBehaviorGroups().getFirst().getRules().getFirst().getName());
    }

    @Test
    @DisplayName("round-trips: what a client posts is what it reads back")
    void roundTrips() throws Exception {
        String posted = """
                { "behaviorGroups": [ { "name": "Greetings",
                    "behaviorRules": [ { "name": "Welcome", "actions": ["welcome_action"] } ] } ] }
                """;

        String readBack = mapper.writeValueAsString(mapper.readValue(posted, RuleSetConfiguration.class));

        assertTrue(readBack.contains("\"behaviorRules\""),
                "a client that posted behaviorRules must not get a different key back: " + readBack);
    }
}
