package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.CounterweightConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Resolves assertiveness counterweight instructions based on configuration.
 * <p>
 * This is a stateless service (NOT an {@code ILifecycleTask}) that converts
 * counterweight configuration into system prompt instructions. The resolved
 * text is appended to the system message by {@link LlmTask} before RAG context
 * injection.
 * <p>
 * <strong>Predefined levels:</strong>
 * <ul>
 * <li>{@code normal} — no modification (empty string)</li>
 * <li>{@code cautious} — intent-declaration and verification requirements</li>
 * <li>{@code strict} — mandatory confirmation for all state-changing
 * operations</li>
 * </ul>
 * Custom {@code instructions} in the config override the predefined level text.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class CounterweightService {

    private static final Logger LOGGER = Logger.getLogger(CounterweightService.class);

    static final String CAUTIOUS_INSTRUCTIONS = """
            - State your planned actions clearly before executing them.
            - Err heavily on the side of caution for state-changing operations.
            - If uncertain about the user's intent, ask for clarification rather than guessing.
            - Prefer reversible actions over irreversible ones when multiple approaches exist.""";

    static final String STRICT_INSTRUCTIONS = """
            - You MUST declare your intent before acting on any request.
            - You MUST NOT make sweeping or broad changes without explicit user confirmation.
            - You MUST request step-by-step confirmation for any state-changing operation.
            - If uncertain, STOP and ask. Never guess or assume.
            - Prefer the most conservative interpretation of ambiguous instructions.
            - Never proceed with destructive operations (delete, overwrite, reset) without explicit approval.""";

    private final MeterRegistry meterRegistry;

    @Inject
    public CounterweightService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        LOGGER.info("CounterweightService initialized");
    }

    /**
     * Resolve the counterweight configuration into system prompt instructions.
     *
     * @param config
     *            the counterweight configuration (may be null)
     * @return the resolved instructions string, or empty string if no counterweight
     *         applies
     */
    public String resolveInstructions(CounterweightConfig config) {
        if (config == null || !config.isEnabled()) {
            return "";
        }

        // Custom instructions override predefined levels
        List<String> custom = config.getInstructions();
        if (custom != null && !custom.isEmpty()) {
            incrementCounter(config.getLevel() != null ? config.getLevel() : "custom");
            return String.join("\n", custom);
        }

        String level = config.getLevel();
        if (isNullOrEmpty(level) || "normal".equalsIgnoreCase(level)) {
            return "";
        }

        incrementCounter(level);

        return switch (level.toLowerCase()) {
            case "cautious" -> CAUTIOUS_INSTRUCTIONS;
            case "strict" -> STRICT_INSTRUCTIONS;
            default -> {
                LOGGER.warnf("Unknown counterweight level '%s', treating as 'normal'", level);
                yield "";
            }
        };
    }

    private void incrementCounter(String level) {
        Counter.builder("eddi.counterweight.activation.count")
                .tag("level", level)
                .register(meterRegistry)
                .increment();
    }
}
