package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone configuration for a channel integration. Decouples channel routing
 * and credentials from {@code AgentConfiguration}, supporting multi-target
 * channels and cross-platform extensibility (Slack, Teams, Discord).
 * <p>
 * One {@code ChannelIntegrationConfiguration} represents one platform channel
 * (e.g., a single Slack channel) with one or more {@link ChannelTarget}s that
 * map trigger keywords to agents or groups.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelIntegrationConfiguration {

    private String name;
    private String channelType;
    private Map<String, String> platformConfig;
    private List<ChannelTarget> targets;
    private String defaultTargetName;

    public ChannelIntegrationConfiguration() {
        this.platformConfig = new HashMap<>();
        this.targets = new ArrayList<>();
    }

    /**
     * Human-readable name for this integration (e.g., "Engineering AI Hub").
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Platform type. Validated server-side against registered adapters. Currently
     * supported: {@code "slack"}. Future: {@code "teams"}, {@code "discord"}.
     * <p>
     * Deliberately a String (not an enum) so downstream forks can register custom
     * adapters without recompiling core.
     */
    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    /**
     * Platform-specific credentials and identifiers. Keys depend on
     * {@link #channelType}:
     * <ul>
     * <li><b>slack:</b> {@code channelId}, {@code botToken},
     * {@code signingSecret}</li>
     * <li><b>teams:</b> {@code channelId}, {@code appId}, {@code appPassword},
     * {@code serviceUrl}</li>
     * <li><b>discord:</b> {@code guildId}, {@code channelId}, {@code botToken},
     * {@code publicKey}</li>
     * </ul>
     * Secret values should use vault references: {@code ${eddivault:key-name}}.
     */
    public Map<String, String> getPlatformConfig() {
        return platformConfig;
    }

    public void setPlatformConfig(Map<String, String> platformConfig) {
        this.platformConfig = platformConfig;
    }

    /**
     * Available targets in this channel. Each target maps trigger keywords to an
     * agent or group. At least one target is required.
     */
    public List<ChannelTarget> getTargets() {
        return targets;
    }

    public void setTargets(List<ChannelTarget> targets) {
        this.targets = targets;
    }

    /**
     * Name of the target to use when no trigger keyword matches. Must reference an
     * existing target in {@link #targets}. Required.
     */
    public String getDefaultTargetName() {
        return defaultTargetName;
    }

    public void setDefaultTargetName(String defaultTargetName) {
        this.defaultTargetName = defaultTargetName;
    }
}
