/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactValidator;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ValidatorKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I17 — the declarative artifact validation chain: save-time spec checks fail
 * the config save with an actionable path, write-time failures come back as
 * rejection sentences for the model, and a broken spec fails closed.
 *
 * @author tests
 */
class ArtifactValidatorsTest {

    private static final String SCHEMA = """
            {"type":"object","required":["title"],"properties":{"title":{"type":"string"}}}""";

    private static ArtifactConfig config(ArtifactValidator... validators) {
        return new ArtifactConfig(true, 5, List.of(validators));
    }

    // =================================================================
    // save-time: requireValidSpecs
    // =================================================================

    @Test
    @DisplayName("valid specs of all three kinds pass the save-time check")
    void validSpecs_pass() {
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(config(
                new ArtifactValidator(ValidatorKind.JSON_SCHEMA, SCHEMA),
                new ArtifactValidator(ValidatorKind.REGEX, "^#"),
                new ArtifactValidator(ValidatorKind.MAX_LENGTH, "1000"))));
    }

    @Test
    @DisplayName("null config or empty chain is a no-op")
    void absentConfig_noOp() {
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(null));
        assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(new ArtifactConfig(true, 5, null)));
    }

    @Test
    @DisplayName("a broken spec fails the save and names the validator's position")
    void brokenSpecs_throwWithPath() {
        var badRegex = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.REGEX, "[unclosed"))));
        assertTrue(badRegex.getMessage().contains("validators[0]"), badRegex.getMessage());

        var badLength = assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(
                        new ArtifactValidator(ValidatorKind.REGEX, "ok"),
                        new ArtifactValidator(ValidatorKind.MAX_LENGTH, "lots"))));
        assertTrue(badLength.getMessage().contains("validators[1]"), badLength.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.MAX_LENGTH, "0"))));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(null, "x"))));
        assertThrows(IllegalArgumentException.class,
                () -> ArtifactValidators.requireValidSpecs(config(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, " "))));
    }

    // =================================================================
    // write-time: firstRejection
    // =================================================================

    @Test
    @DisplayName("content passing the whole chain yields null")
    void passingContent_null() {
        var validators = List.of(
                new ArtifactValidator(ValidatorKind.MAX_LENGTH, "100"),
                new ArtifactValidator(ValidatorKind.REGEX, "title"),
                new ArtifactValidator(ValidatorKind.JSON_SCHEMA, SCHEMA));

        assertNull(ArtifactValidators.firstRejection(validators, "{\"title\":\"ok\"}"));
    }

    @Test
    @DisplayName("MAX_LENGTH rejection names both counts so the model can act")
    void maxLength_rejects() {
        String rejection = ArtifactValidators.firstRejection(
                List.of(new ArtifactValidator(ValidatorKind.MAX_LENGTH, "5")), "123456");

        assertNotNull(rejection);
        assertTrue(rejection.contains("6") && rejection.contains("5"), rejection);
    }

    @Test
    @DisplayName("REGEX requires a match somewhere in the content")
    void regex_rejectsAndPasses() {
        var validators = List.of(new ArtifactValidator(ValidatorKind.REGEX, "^# .+"));

        assertNull(ArtifactValidators.firstRejection(validators, "# Heading\nbody"));
        String rejection = ArtifactValidators.firstRejection(validators, "no heading here");
        assertNotNull(rejection);
        assertTrue(rejection.contains("pattern"), rejection);
    }

    @Test
    @DisplayName("JSON_SCHEMA distinguishes 'not JSON' from 'JSON that violates the schema'")
    void jsonSchema_rejections() {
        var validators = List.of(new ArtifactValidator(ValidatorKind.JSON_SCHEMA, SCHEMA));

        String notJson = ArtifactValidators.firstRejection(validators, "plain prose");
        assertNotNull(notJson);
        assertTrue(notJson.contains("valid JSON"), notJson);

        String violates = ArtifactValidators.firstRejection(validators, "{\"other\":1}");
        assertNotNull(violates);
        assertTrue(violates.contains("title"), "the violation message must name what is missing: " + violates);

        assertNull(ArtifactValidators.firstRejection(validators, "{\"title\":\"x\"}"));
    }

    @Test
    @DisplayName("the chain runs in config order — the first failure wins")
    void chainOrder_firstFailureWins() {
        var validators = List.of(
                new ArtifactValidator(ValidatorKind.MAX_LENGTH, "3"),
                new ArtifactValidator(ValidatorKind.REGEX, "nope"));

        String rejection = ArtifactValidators.firstRejection(validators, "12345");
        assertNotNull(rejection);
        assertTrue(rejection.contains("character"), "the length failure comes first: " + rejection);
    }

    @Test
    @DisplayName("a broken spec at write time fails closed — the write is refused, never admitted")
    void brokenSpecAtWriteTime_failsClosed() {
        assertNotNull(ArtifactValidators.firstRejection(
                List.of(new ArtifactValidator(ValidatorKind.REGEX, "[unclosed")), "anything"));
        assertNotNull(ArtifactValidators.firstRejection(
                List.of(new ArtifactValidator(ValidatorKind.MAX_LENGTH, "many")), "anything"));
        assertNotNull(ArtifactValidators.firstRejection(
                List.of(new ArtifactValidator(null, null)), "anything"));
    }
}
