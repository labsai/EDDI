/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.templates;

import ai.labs.eddi.configs.groups.ArtifactValidators;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.mongo.AgentGroupStore;
import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I10 — {@link GroupTemplateService}. The instantiation-with-validation test is
 * deliberately the plan's integration test of the WHOLE Wave 1–3 config
 * surface: every packaged template must materialize into a config that passes
 * every save-time validator, exactly as a hand-written config would.
 */
class GroupTemplateServiceTest {

    private GroupTemplateService service;

    @BeforeEach
    void setUp() {
        service = new GroupTemplateService(new ObjectMapper());
        service.loadTemplates();
    }

    /** Dummy hex agent ids for every role a template declares. */
    private Map<String, String> dummyAssignments(String templateId) {
        var template = service.find(templateId);
        assertNotNull(template, templateId);
        Map<String, String> assignments = new HashMap<>();
        int i = 0;
        for (var role : template.manifest().requiredRoles()) {
            assignments.put(role.role(), "5f4e3d2c1b0a998877665%02d".formatted(i++));
        }
        return assignments;
    }

    @Test
    void allFiveTemplates_load_inIndexOrder() {
        assertEquals(List.of("research-pod", "editorial-team", "ops-task-force", "decision-board", "negotiation-table"),
                service.list().stream().map(GroupTemplateService.TemplateManifest::templateId).toList());
        for (var manifest : service.list()) {
            assertFalse(manifest.title().isBlank());
            assertFalse(manifest.description().isBlank());
            assertFalse(manifest.requiredRoles().isEmpty(), manifest.templateId() + " must declare its roles");
        }
    }

    @Test
    @DisplayName("every template instantiates AND passes every save-time validator — the Wave 1–3 integration test")
    void everyTemplate_instantiates_andPassesAllSaveTimeValidation() {
        for (var manifest : service.list()) {
            String id = manifest.templateId();
            AgentGroupConfiguration config = service.instantiate(id, "Instance of " + id, dummyAssignments(id));

            // No placeholder survives instantiation.
            assertNotNull(config.getMembers(), id);
            config.getMembers().forEach(m -> assertFalse(m.agentId().startsWith("$"),
                    id + " left a placeholder member: " + m.agentId()));
            if (config.getModeratorAgentId() != null) {
                assertFalse(config.getModeratorAgentId().startsWith("$"), id + " left a placeholder moderator");
            }

            // The full save-time validation matrix, exactly as create()/update()
            // run it. A validator throw here means the packaged template ships a
            // config the store would refuse — the defect this test exists to catch.
            assertDoesNotThrow(() -> HitlConfigValidation.validate(config.getHitlConfig()), id);
            assertDoesNotThrow(() -> AgentGroupStore.validateVotePhases(config), id);
            assertTrue(AgentGroupStore.humanMemberProblems(config).isEmpty(),
                    id + ": " + AgentGroupStore.humanMemberProblems(config));
            assertDoesNotThrow(() -> AgentGroupStore.validateFacilitator(config), id);
            assertDoesNotThrow(() -> ArtifactValidators.requireValidSpecs(config.getArtifactConfig()), id);

            // Every template must resolve to a non-empty phase list.
            List<AgentGroupConfiguration.DiscussionPhase> phases = config.getPhases() != null && !config.getPhases().isEmpty()
                    ? config.getPhases()
                    : DiscussionStylePresets.expand(config.getStyle(), config.getMaxRounds());
            assertFalse(phases.isEmpty(), id + " must yield phases");
        }
    }

    @Test
    void decisionBoard_carriesTheHumanDirector_asAHumanMember() {
        var config = service.instantiate("decision-board", null, dummyAssignments("decision-board"));

        assertTrue(config.getMembers().stream().anyMatch(m -> m.memberType() == MemberType.HUMAN),
                "the human director must survive instantiation as a HUMAN member");
        assertNotNull(config.getHumanMemberConfig());
        assertEquals("PT24H", config.getHumanMemberConfig().turnTimeout());
    }

    @Test
    void negotiationTable_usesTheNegotiationPreset() {
        var config = service.instantiate("negotiation-table", null, dummyAssignments("negotiation-table"));

        assertEquals(DiscussionStyle.NEGOTIATION, config.getStyle());
        assertNull(config.getPhases(), "the preset expands at run time — the template stores no phases");
        assertFalse(DiscussionStylePresets.expand(DiscussionStyle.NEGOTIATION, config.getMaxRounds()).isEmpty());
    }

    @Test
    void instantiate_missingRole_failsNamingIt() {
        var assignments = dummyAssignments("research-pod");
        assignments.remove("moderator");

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.instantiate("research-pod", null, assignments));
        assertTrue(ex.getMessage().contains("moderator"), ex.getMessage());
    }

    @Test
    void instantiate_unknownRole_failsListingTheValidOnes() {
        var assignments = dummyAssignments("research-pod");
        assignments.put("stenographer", "agent-x");

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.instantiate("research-pod", null, assignments));
        assertTrue(ex.getMessage().contains("stenographer"), ex.getMessage());
        assertTrue(ex.getMessage().contains("researcher1"), "the error lists the template's real roles: " + ex.getMessage());
    }

    @Test
    void instantiate_unknownTemplate_fails() {
        assertThrows(IllegalArgumentException.class, () -> service.instantiate("no-such", null, Map.of()));
    }

    @Test
    void instantiate_customName_overridesTheTemplateName() {
        var config = service.instantiate("research-pod", "Quantum Pod", dummyAssignments("research-pod"));
        assertEquals("Quantum Pod", config.getName());

        var unnamed = service.instantiate("research-pod", "  ", dummyAssignments("research-pod"));
        assertEquals("Research Pod", unnamed.getName(), "blank keeps the template's name");
    }
}
