package ai.labs.eddi.configs.pipelines.model;


import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class PipelineConfiguration {
    private List<PipelineStep> PipelineSteps = new LinkedList<>();

    public static class PipelineStep {
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

    public PipelineConfiguration() {
    }

    public List<PipelineStep> getPipelineSteps() {
        return PipelineSteps;
    }

    public void setPipelineSteps(List<PipelineStep> PipelineSteps) {
        this.PipelineSteps = PipelineSteps;
    }
}
