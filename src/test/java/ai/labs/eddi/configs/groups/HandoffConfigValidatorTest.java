/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptPolicy;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.DecisionRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.GroupHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.TeamHandoffConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandoffConfigValidatorTest {

    private static AgentGroupConfiguration group(GroupHandoffConfig cfg) {
        var g = new AgentGroupConfiguration();
        g.setHandoffConfig(cfg);
        return g;
    }

    private static GroupHandoffConfig withTargets(HandoffTarget... t) {
        return new GroupHandoffConfig(List.of(t), null, 0, 0, 0, null, null);
    }

    private static GroupHandoffConfig withRules(List<HandoffTarget> targets, DecisionRule rule) {
        return new GroupHandoffConfig(targets, null, 0, 0, 0, List.of(rule), null);
    }

    private static HandoffTarget target(String alias) {
        return new HandoffTarget(alias, "g-" + alias, "desc", null);
    }

    private static String message(Runnable r) {
        return assertThrows(IllegalArgumentException.class, r::run).getMessage();
    }

    @Test
    void nullConfigIsValid() {
        assertDoesNotThrow(() -> HandoffConfigValidator.validate(group(null)));
        assertDoesNotThrow(() -> HandoffConfigValidator.validate(null));
        assertDoesNotThrow(() -> HandoffConfigValidator.validateTask(null, "p"));
    }

    @Test
    void anUnknownTargetGroupIdIsAcceptedBecauseImportMustNotBreak() {
        assertDoesNotThrow(() -> HandoffConfigValidator.validate(group(withTargets(new HandoffTarget("desk", "does-not-exist", null, null)))));
    }

    @Test
    void badAliasNamesItsFieldPath() {
        String msg = message(() -> HandoffConfigValidator.validate(group(withTargets(target("ok"), target("Bad Alias")))));
        assertTrue(msg.contains("handoffConfig.targets[1].alias"), msg);
    }

    @Test
    void duplicateAliasIsRejected() {
        String msg = message(() -> HandoffConfigValidator.validate(group(withTargets(target("a"), target("a")))));
        assertTrue(msg.contains("declared twice"), msg);
    }

    @Test
    void blankGroupIdAndLongDescriptionAreRejected() {
        assertTrue(message(() -> HandoffConfigValidator.validate(group(withTargets(new HandoffTarget("a", " ", null, null))))).contains("groupId"));
        assertTrue(message(() -> HandoffConfigValidator.validate(group(withTargets(new HandoffTarget("a", "g", "x".repeat(301), null)))))
                .contains("description"));
    }

    @Test
    void tooManyTargetsAreRejected() {
        var many = new ArrayList<HandoffTarget>();
        for (int i = 0; i < 21; i++) {
            many.add(target("t" + i));
        }
        String msg = message(() -> HandoffConfigValidator.validate(group(new GroupHandoffConfig(many, null, 0, 0, 0, null, null))));
        assertTrue(msg.contains("at most 20"), msg);
    }

    @Test
    void taskLevelTargetsAreValidatedWithTheirPath() {
        var cfg = new TeamHandoffConfig(List.of(target("UPPER")), 0, 0, 0);
        String msg = message(() -> HandoffConfigValidator.validateTask(cfg, "langchain.task[0].teamHandoff"));
        assertTrue(msg.startsWith("langchain.task[0].teamHandoff.targets[0].alias"), msg);
    }

    @Test
    void onDecisionTargetMustBeADeclaredAlias() {
        var rule = new DecisionRule("nope", List.of("VOTE"), true, "s", null, null);
        String msg = message(() -> HandoffConfigValidator.validate(group(withRules(List.of(target("desk")), rule))));
        assertTrue(msg.contains("onDecision[0].target"), msg);
    }

    @Test
    void onDecisionTypesSubjectAndTemplateLengthAreChecked() {
        var targets = List.of(target("desk"));
        var noTypes = new DecisionRule("desk", List.of(), true, "s", null, null);
        assertTrue(message(() -> HandoffConfigValidator.validate(group(withRules(targets, noTypes)))).contains("decisionTypes"));
        var badType = new DecisionRule("desk", List.of("NONE"), true, "s", null, null);
        assertTrue(message(() -> HandoffConfigValidator.validate(group(withRules(targets, badType)))).contains("decisionTypes"));
        var noSubject = new DecisionRule("desk", List.of("VOTE"), true, " ", null, null);
        assertTrue(message(() -> HandoffConfigValidator.validate(group(withRules(targets, noSubject)))).contains("subjectTemplate"));
        var longDesc = new DecisionRule("desk", List.of("VOTE"), true, "s", "x".repeat(1001), null);
        assertTrue(message(() -> HandoffConfigValidator.validate(group(withRules(targets, longDesc)))).contains("descriptionTemplate"));
        var ok = new DecisionRule("desk", List.of("VOTE", "VERDICT"), true, "s {decision.winner}", null, null);
        assertDoesNotThrow(() -> HandoffConfigValidator.validate(group(withRules(targets, ok))));
    }

    @Test
    void sourceSyntaxIsEnforcedAndWildcardsRefused() {
        for (String bad : new String[]{"group:*", "agent:*", "*", "team:abc", "agent:", "abc", "group:a b"}) {
            var accept = new AcceptPolicy(List.of(new AcceptRule(bad, IntakeMode.REVIEW, null)), 0, 0);
            String msg = message(() -> HandoffConfigValidator.validate(group(new GroupHandoffConfig(null, null, 0, 0, 0, null, accept))));
            assertTrue(msg.contains("handoffConfig.accept.from[0].source"), bad + " -> " + msg);
        }
        var good = new AcceptPolicy(List.of(new AcceptRule("agent:66bb", null, null), new AcceptRule("group:66aa", IntakeMode.AUTO, 5)), 0, 0);
        assertDoesNotThrow(() -> HandoffConfigValidator.validate(group(new GroupHandoffConfig(null, null, 0, 0, 0, null, good))));
    }

    @Test
    void duplicateAcceptSourceIsRejected() {
        var accept = new AcceptPolicy(List.of(new AcceptRule("group:a", null, null), new AcceptRule("group:a", IntakeMode.AUTO, null)), 0, 0);
        String msg = message(() -> HandoffConfigValidator.validate(group(new GroupHandoffConfig(null, null, 0, 0, 0, null, accept))));
        assertTrue(msg.contains("listed twice"), msg);
    }

    @Test
    void toolParticipantsGrammar() {
        for (String ok : new String[]{"ALL", "MODERATOR", "ROLE:judge"}) {
            assertDoesNotThrow(() -> HandoffConfigValidator.validate(group(new GroupHandoffConfig(null, ok, 0, 0, 0, null, null))), ok);
        }
        for (String bad : new String[]{"all", "ROLE:", "ROLE:  ", "EVERYONE"}) {
            assertTrue(message(() -> HandoffConfigValidator.validate(group(new GroupHandoffConfig(null, bad, 0, 0, 0, null, null))))
                    .contains("toolParticipants"), bad);
        }
    }
}
