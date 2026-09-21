/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-time validation of the group configuration body.
 * <p>
 * {@code maxRounds} is the clearest example in the config layer of a value that
 * sizes a loop: {@code DiscussionStylePresets.expand} multiplies it into
 * discussion phases and every phase fans out one LLM call per member. It was
 * accepted at any magnitude, and {@code Math.max(maxRounds, 1)} guarded only
 * the low side — so the agent author got no feedback at save time and the group
 * looked healthy.
 */
class AgentGroupConfigurationValidationTest {

    private static Validator validator;

    @BeforeAll
    static void bootstrapValidator() {
        validator = Validation.byProvider(HibernateValidator.class)
                .configure()
                // Avoids requiring a Jakarta EL implementation; {value}/{max}
                // placeholders are still substituted.
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()
                .getValidator();
    }

    private static AgentGroupConfiguration group() {
        var config = new AgentGroupConfiguration();
        config.setName("Architecture review board");
        config.setStyle(DiscussionStyle.ROUND_TABLE);
        config.setMembers(new ArrayList<>(List.of(
                new GroupMember("agent-1", "Alice", 1, null, MemberType.AGENT),
                new GroupMember("agent-2", "Bob", 2, null, MemberType.AGENT))));
        return config;
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    private static String messageFor(Set<? extends ConstraintViolation<?>> violations, String path) {
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(path))
                .map(ConstraintViolation::getMessage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no violation on '" + path + "', got " + paths(violations)));
    }

    @Test
    @DisplayName("a realistic group configuration produces no violations")
    void realisticConfigurationPasses() {
        var config = group();
        config.setMaxRounds(3);

        assertTrue(validator.validate(config).isEmpty());
    }

    @Test
    @DisplayName("maxRounds at the ceiling is still accepted")
    void maxRoundsAtCeilingPasses() {
        var config = group();
        config.setMaxRounds(AgentGroupConfiguration.MAX_DISCUSSION_ROUNDS);

        assertTrue(validator.validate(config).isEmpty());
    }

    @Test
    @DisplayName("maxRounds beyond the ceiling is rejected at save time, naming the field and the limit")
    void oversizedMaxRoundsRejected() {
        var config = group();
        config.setMaxRounds(AgentGroupConfiguration.MAX_DISCUSSION_ROUNDS + 1);

        var violations = validator.validate(config);

        assertTrue(paths(violations).contains("maxRounds"), "got " + paths(violations));
        String message = messageFor(violations, "maxRounds");
        assertTrue(message.contains("'maxRounds'"), message);
        assertTrue(message.contains(String.valueOf(AgentGroupConfiguration.MAX_DISCUSSION_ROUNDS)), message);
    }

    @Test
    @DisplayName("an absurd maxRounds is rejected rather than expanded into phases")
    void absurdMaxRoundsRejected() {
        var config = group();
        config.setMaxRounds(1_000_000);

        assertTrue(paths(validator.validate(config)).contains("maxRounds"));
    }

    @Test
    @DisplayName("maxRounds of 0 stays legal — expand() already clamps the low side")
    void zeroMaxRoundsStillAccepted() {
        var config = group();
        config.setMaxRounds(0);

        assertTrue(validator.validate(config).isEmpty());
    }

    @Test
    @DisplayName("a member list beyond the fan-out ceiling is rejected")
    void oversizedMemberListRejected() {
        var config = group();
        var members = new ArrayList<GroupMember>();
        for (int i = 0; i <= AgentGroupConfiguration.MAX_MEMBERS; i++) {
            members.add(new GroupMember("agent-" + i, "Agent " + i, i, null, MemberType.AGENT));
        }
        config.setMembers(members);

        var violations = validator.validate(config);

        assertTrue(paths(violations).contains("members"), "got " + paths(violations));
        assertTrue(messageFor(violations, "members").contains("'members'"));
    }

    @Test
    @DisplayName("a member list at the ceiling is accepted")
    void memberListAtCeilingPasses() {
        var config = group();
        var members = new ArrayList<GroupMember>();
        for (int i = 0; i < AgentGroupConfiguration.MAX_MEMBERS; i++) {
            members.add(new GroupMember("agent-" + i, "Agent " + i, i, null, MemberType.AGENT));
        }
        config.setMembers(members);

        assertTrue(validator.validate(config).isEmpty());
    }

    /**
     * The constraints above only fire in production if the store's body parameters
     * are marked for cascaded validation.
     */
    @Test
    @DisplayName("createGroup/updateGroup bodies carry @NotNull and @Valid")
    void storeBodyParametersAreValidated() {
        var unguarded = new ArrayList<String>();
        int checked = 0;

        for (Method method : IRestAgentGroupStore.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getType() != AgentGroupConfiguration.class) {
                    continue;
                }
                checked++;
                if (parameter.getAnnotation(Valid.class) == null) {
                    unguarded.add(method.getName() + " is missing @Valid");
                }
                if (parameter.getAnnotation(NotNull.class) == null) {
                    unguarded.add(method.getName() + " is missing @NotNull");
                }
            }
        }

        assertTrue(unguarded.isEmpty(), () -> "unvalidated request bodies: " + unguarded);
        assertEquals(2, checked, "expected createGroup and updateGroup to be guarded");
    }
}
