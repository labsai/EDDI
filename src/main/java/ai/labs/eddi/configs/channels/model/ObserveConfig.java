package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for passive channel observation. Controls when an observer
 * target decides to respond to channel messages it silently monitors.
 * <p>
 * Cost control follows EDDI convention (§4.7 of AGENTS.md): dollar-based
 * ceiling ({@link #maxCostPerDay}) is primary; call count
 * ({@link #maxDailyResponses}) is a secondary hard cap.
 * <p>
 * <b>Note:</b> Schema-ready; implementation deferred to a future PR.
 *
 * @since 6.1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObserveConfig {

    private List<String> triggerKeywords;
    private List<String> triggerMimeTypes;
    private int cooldownSeconds = 60;
    private int maxDailyResponses = 50;
    private double maxCostPerDay = 5.0;

    public ObserveConfig() {
        this.triggerKeywords = new ArrayList<>();
        this.triggerMimeTypes = new ArrayList<>();
    }

    /**
     * Only invoke the agent if the message contains one of these keywords
     * (case-insensitive substring match). Empty list = match all messages.
     */
    public List<String> getTriggerKeywords() {
        return triggerKeywords;
    }

    public void setTriggerKeywords(List<String> triggerKeywords) {
        this.triggerKeywords = triggerKeywords;
    }

    /**
     * Trigger on file attachments with these MIME types (e.g.,
     * {@code "application/pdf"}). Empty list = don't trigger on file types.
     */
    public List<String> getTriggerMimeTypes() {
        return triggerMimeTypes;
    }

    public void setTriggerMimeTypes(List<String> triggerMimeTypes) {
        this.triggerMimeTypes = triggerMimeTypes;
    }

    /**
     * Minimum seconds between responses in observe mode (prevents spam). Default:
     * 60.
     */
    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    /**
     * Hard cap on number of responses per day. Secondary control — use
     * {@link #maxCostPerDay} as the primary ceiling. Default: 50.
     */
    public int getMaxDailyResponses() {
        return maxDailyResponses;
    }

    public void setMaxDailyResponses(int maxDailyResponses) {
        this.maxDailyResponses = maxDailyResponses;
    }

    /**
     * Dollar-based cost ceiling per day. Primary cost control. Default: $5.00.
     */
    public double getMaxCostPerDay() {
        return maxCostPerDay;
    }

    public void setMaxCostPerDay(double maxCostPerDay) {
        this.maxCostPerDay = maxCostPerDay;
    }
}
