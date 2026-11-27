/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The save-time warning of {@code healthConfig.externalRefs} (plan §4.5): which
 * references no {@code property.json} of the agent's workflows sets. A warning,
 * never a refusal — a reference can also be set by an HTTP call's
 * {@code postResponse}, a {@code preRequest} or the model, which are not looked
 * at — answered in the {@code X-Eddi-Warnings} header as
 * {@code health-ref-not-set:<name>}.
 * <p>
 * Best effort by contract: a workflow or property setter that cannot be read is
 * skipped, and {@link #warnings} never throws.
 */
@ApplicationScoped
public class HealthRefDeclarations {

    private static final Logger LOGGER = Logger.getLogger(HealthRefDeclarations.class);

    /** The warning code, followed by {@code :<name>}. */
    public static final String WARNING = "health-ref-not-set";

    private static final String PROPERTY_AUTHORITY = "ai.labs.property";

    private final IWorkflowStore workflowStore;
    private final IResourceClientLibrary resourceClientLibrary;

    @Inject
    public HealthRefDeclarations(IWorkflowStore workflowStore, IResourceClientLibrary resourceClientLibrary) {
        this.workflowStore = workflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
    }

    /**
     * One {@code health-ref-not-set:<name>} per reference no property.json sets.
     */
    public List<String> warnings(AgentConfiguration configuration) {
        if (configuration == null || configuration.getHealthConfig() == null || configuration.getHealthConfig().getExternalRefs() == null
                || configuration.getHealthConfig().getExternalRefs().isEmpty()) {
            return List.of();
        }
        try {
            return HealthConfigValidator.refsNotSet(configuration, propertyNames(configuration)).stream().map(name -> WARNING + ":" + name).toList();
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not check the health references of an agent: %s", e.getMessage());
            return List.of();
        }
    }

    /** The property names the agent's {@code property.json} files set. */
    Set<String> propertyNames(AgentConfiguration configuration) {
        Set<String> names = new LinkedHashSet<>();
        if (configuration.getWorkflows() == null) {
            return names;
        }
        for (URI workflowUri : configuration.getWorkflows()) {
            IResourceStore.IResourceId workflowId = RestUtilities.extractResourceId(workflowUri);
            if (workflowId == null || workflowId.getId() == null || workflowId.getVersion() == null) {
                continue;
            }
            WorkflowConfiguration workflow;
            try {
                workflow = workflowStore.read(workflowId.getId(), workflowId.getVersion());
            } catch (Exception e) {
                continue;
            }
            if (workflow == null || workflow.getWorkflowSteps() == null) {
                continue;
            }
            for (var step : workflow.getWorkflowSteps()) {
                if (step.getConfig() == null || !(step.getConfig().get("uri") instanceof String uri)) {
                    continue;
                }
                try {
                    URI reference = URI.create(uri);
                    if (!PROPERTY_AUTHORITY.equals(reference.getHost())) {
                        continue;
                    }
                    PropertySetterConfiguration setter = resourceClientLibrary.getResource(reference, PropertySetterConfiguration.class);
                    if (setter == null || setter.getSetOnActions() == null) {
                        continue;
                    }
                    for (SetOnActions onActions : setter.getSetOnActions()) {
                        if (onActions == null || onActions.getSetProperties() == null) {
                            continue;
                        }
                        for (PropertyInstruction instruction : onActions.getSetProperties()) {
                            if (instruction != null && instruction.getName() != null) {
                                names.add(instruction.getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    // skipped: best effort
                }
            }
        }
        return names;
    }
}
