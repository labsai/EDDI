/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestGroupTemplates.InstantiateRequest;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.templates.GroupTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I10 — {@link RestGroupTemplates}: listing, reading, and instantiation through
 * the normal store path.
 */
class RestGroupTemplatesTest {

    private GroupTemplateService templateService;
    private IRestAgentGroupStore groupStore;
    private RestGroupTemplates rest;

    @BeforeEach
    void setUp() {
        templateService = new GroupTemplateService(new ObjectMapper());
        templateService.loadTemplates();
        groupStore = mock(IRestAgentGroupStore.class);
        rest = new RestGroupTemplates(templateService, groupStore);
    }

    private Map<String, String> assignments(String templateId) {
        Map<String, String> map = new HashMap<>();
        int i = 0;
        for (var role : templateService.find(templateId).manifest().requiredRoles()) {
            map.put(role.role(), "5f4e3d2c1b0a998877665%02d".formatted(i++));
        }
        return map;
    }

    @Test
    void listTemplates_returnsAllManifests() {
        var response = rest.listTemplates();

        assertEquals(200, response.getStatus());
        assertEquals(5, ((List<?>) response.getEntity()).size());
    }

    @Test
    void readTemplate_foundAnd404() {
        assertEquals(200, rest.readTemplate("research-pod").getStatus());
        assertEquals(404, rest.readTemplate("no-such").getStatus());
    }

    @Test
    @DisplayName("instantiation saves through the NORMAL store path — no validation bypass")
    void instantiate_savesThroughTheStorePath() throws Exception {
        when(groupStore.createGroup(any())).thenReturn(
                Response.created(URI.create("/groupstore/groups/abc?version=1")).build());

        var response = rest.instantiate("research-pod",
                new InstantiateRequest("My Pod", assignments("research-pod")));

        assertEquals(201, response.getStatus());
        ArgumentCaptor<AgentGroupConfiguration> captor = ArgumentCaptor.forClass(AgentGroupConfiguration.class);
        verify(groupStore).createGroup(captor.capture());
        var config = captor.getValue();
        assertEquals("My Pod", config.getName());
        config.getMembers().forEach(m -> assertFalse(m.agentId().startsWith("$")));
    }

    @Test
    void instantiate_missingRole_400_andNothingSaved() throws Exception {
        var partial = assignments("research-pod");
        partial.remove("researcher2");

        var response = rest.instantiate("research-pod", new InstantiateRequest(null, partial));

        assertEquals(400, response.getStatus());
        assertTrue(String.valueOf(response.getEntity()).contains("researcher2"));
        verify(groupStore, never()).createGroup(any());
    }

    @Test
    void instantiate_unknownTemplate_400() throws Exception {
        assertEquals(400, rest.instantiate("no-such", new InstantiateRequest(null, Map.of())).getStatus());
        verify(groupStore, never()).createGroup(any());
    }
}
