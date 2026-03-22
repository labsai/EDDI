package ai.labs.eddi.configs.agents.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class AgentConfiguration {
    @JsonAlias("packages")
    private List<URI> workflows = new ArrayList<>();
    private List<ChannelConnector> channels = new ArrayList<>();

    public static class ChannelConnector {
        private URI type;
        private Map<String, String> config = new HashMap<>();

        public URI getType() {
            return type;
        }

        public void setType(URI type) {
            this.type = type;
        }

        public Map<String, String> getConfig() {
            return config;
        }

        public void setConfig(Map<String, String> config) {
            this.config = config;
        }
    }


    public List<URI> getWorkflows() {
        return workflows;
    }

    public void setWorkflows(List<URI> workflows) {
        this.workflows = workflows;
    }

    public List<ChannelConnector> getChannels() {
        return channels;
    }

    public void setChannels(List<ChannelConnector> channels) {
        this.channels = channels;
    }
}
