/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.IRestGroupTemplates;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.templates.GroupTemplateService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * REST implementation for {@link IRestGroupTemplates} (I10). Instantiation
 * saves through {@link IRestAgentGroupStore#createGroup} — the SAME path a
 * hand-written config takes, so versioning, descriptor sync and every save-time
 * validator (vote phases, human members, facilitator, artifact specs, HITL
 * config) apply identically.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestGroupTemplates implements IRestGroupTemplates {

    private static final Logger LOG = Logger.getLogger(RestGroupTemplates.class);

    private final GroupTemplateService templateService;
    private final IRestAgentGroupStore groupStore;

    @Inject
    public RestGroupTemplates(GroupTemplateService templateService, IRestAgentGroupStore groupStore) {
        this.templateService = templateService;
        this.groupStore = groupStore;
    }

    @Override
    public Response listTemplates() {
        return Response.ok(templateService.list()).build();
    }

    @Override
    public Response readTemplate(String templateId) {
        var template = templateService.find(templateId);
        if (template == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No such template: " + templateId)).build();
        }
        return Response.ok(Map.of("manifest", template.manifest(), "config", template.configNode())).build();
    }

    @Override
    public Response instantiate(String templateId, InstantiateRequest request) {
        try {
            AgentGroupConfiguration config = templateService.instantiate(templateId,
                    request != null ? request.name() : null,
                    request != null ? request.roleAssignments() : null);
            // The normal store path — a template earns no validation bypass.
            return groupStore.createGroup(config);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to instantiate template %s", sanitize(templateId));
            return Response.serverError().build();
        }
    }
}
