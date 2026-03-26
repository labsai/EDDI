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

    /**
     * Opt-in flag — when true, this agent is exposed via A2A protocol for
     * inter-agent discovery and communication.
     */
    private boolean a2aEnabled = false;

    /**
     * Skills to advertise in the A2A Agent Card. Each skill describes a capability
     * (e.g., "translation", "code-review"). If empty, a single default skill
     * derived from the agent description is used.
     */
    private List<String> a2aSkills = new ArrayList<>();

    /** Human-readable description for the A2A Agent Card */
    private String description;

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

    public boolean isA2aEnabled() {
        return a2aEnabled;
    }

    public void setA2aEnabled(boolean a2aEnabled) {
        this.a2aEnabled = a2aEnabled;
    }

    public List<String> getA2aSkills() {
        return a2aSkills;
    }

    public void setA2aSkills(List<String> a2aSkills) {
        this.a2aSkills = a2aSkills;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
