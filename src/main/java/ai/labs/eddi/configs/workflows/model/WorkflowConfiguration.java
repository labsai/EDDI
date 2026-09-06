/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * @author ginccc
 */

public class WorkflowConfiguration {
    private List<WorkflowStep> workflowSteps = new LinkedList<>();

    public static class WorkflowStep {
        private URI type;
        private Map<String, Object> extensions = new HashMap<>();
        private Map<String, Object> config = new HashMap<>();

        public URI getType() {
            return type;
        }

        public void setType(URI type) {
            this.type = type;
        }

        public Map<String, Object> getExtensions() {
            return extensions;
        }

        public void setExtensions(Map<String, Object> extensions) {
            this.extensions = extensions;
        }

        public Map<String, Object> getConfig() {
            return config;
        }

        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }

    public WorkflowConfiguration() {
    }

    public List<WorkflowStep> getWorkflowSteps() {
        return workflowSteps;
    }

    /**
     * Retired names for this property, kept readable so stored documents and
     * exported ZIPs keep loading. Jackson still writes {@code workflowSteps}.
     * <ul>
     * <li>{@code packageExtensions} — what EDDI 5.x actually persisted
     * ({@code PackageConfiguration.packageExtensions}, up to and including 5.6.0)
     * and what a 5.x export ZIP carries in its {@code .package.json} entries, which
     * {@code RestImportService} deliberately still accepts. Neither
     * {@code V6RenameMigration} (it renames the {@code packages} collection and the
     * agent's {@code packages} field, not this document's payload key) nor any
     * other migration rewrites it.</li>
     * <li>{@code workflowExtensions} / {@code pipelineSteps} — intermediate names
     * that existed only between v6 development commits. Harmless to keep, and
     * cheaper than being wrong about which of them ever reached a database.</li>
     * </ul>
     * Without these, {@code FAIL_ON_UNKNOWN_PROPERTIES=false} (the deliberate
     * setting in {@code SerializationCustomizer}) drops the key in silence and the
     * workflow loads with ZERO steps: the agent deploys, runs no lifecycle task at
     * all and answers nothing, with no error anywhere.
     */
    @JsonAlias({"packageExtensions", "workflowExtensions", "pipelineSteps"})
    public void setWorkflowSteps(List<WorkflowStep> workflowSteps) {
        this.workflowSteps = workflowSteps;
    }
}
