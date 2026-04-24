/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.model;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

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

    // Note: Alias required for backward compatibility with v5 exported agent
    // schemas (ZIP files / MongoDB)
    // where the property was natively named "workflowExtensions" instead of
    // "workflowSteps".
    @com.fasterxml.jackson.annotation.JsonAlias("workflowExtensions")
    public void setWorkflowSteps(List<WorkflowStep> workflowSteps) {
        this.workflowSteps = workflowSteps;
    }
}
