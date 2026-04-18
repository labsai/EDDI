package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A single addressable target within a channel integration. Maps trigger
 * keywords to an agent or group.
 * <p>
 * Users address a target by typing {@code triggerKeyword: message} in the
 * channel. If no trigger matches, the channel's default target handles the
 * message.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChannelTarget {

    /**
     * Target type — either a single agent or a multi-agent group discussion.
     */
    public enum TargetType {
        AGENT, GROUP
    }

    private String name;
    private List<String> triggers;
    private TargetType type;
    private String targetId;
    private boolean observeMode;
    private ObserveConfig observeConfig;

    public ChannelTarget() {
        this.triggers = new ArrayList<>();
        this.type = TargetType.AGENT;
    }

    /**
     * Display name shown in help messages (e.g., "Architect", "Review Panel").
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Exact trigger keywords (case-insensitive). The user types one of these
     * followed by a colon to address this target: {@code architect: question}.
     */
    public List<String> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<String> triggers) {
        this.triggers = triggers;
    }

    /**
     * Whether this target addresses a single agent or a group discussion.
     */
    public TargetType getType() {
        return type;
    }

    public void setType(TargetType type) {
        this.type = type;
    }

    /**
     * The agent ID or group ID this target routes to, depending on {@link #type}.
     */
    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    /**
     * If {@code true}, this target passively observes all channel messages and
     * selectively responds based on {@link #observeConfig} filters.
     * <p>
     * <b>Note:</b> Observe mode is schema-ready but implementation is deferred.
     */
    public boolean isObserveMode() {
        return observeMode;
    }

    public void setObserveMode(boolean observeMode) {
        this.observeMode = observeMode;
    }

    /**
     * Configuration for passive observation — only meaningful when
     * {@link #observeMode} is {@code true}. Set to {@code null} otherwise.
     */
    public ObserveConfig getObserveConfig() {
        return observeConfig;
    }

    public void setObserveConfig(ObserveConfig observeConfig) {
        this.observeConfig = observeConfig;
    }
}
