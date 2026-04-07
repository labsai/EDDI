package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.IdentityMaskingConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Resolves identity masking configuration into system prompt instructions.
 * <p>
 * This is a stateless service that converts identity masking config into
 * directives appended to the system message. It controls how the agent presents
 * itself (display name, model concealment, custom persona).
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class IdentityMaskingService {

    private final MeterRegistry meterRegistry;

    @Inject
    public IdentityMaskingService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Resolve the identity masking configuration into system prompt instructions.
     *
     * @param config
     *            the identity masking configuration (may be null)
     * @return the resolved instructions string, or empty string if no masking
     *         applies
     */
    public String resolveInstructions(IdentityMaskingConfig config) {
        if (config == null || !config.isEnabled()) {
            return "";
        }

        List<String> directives = new ArrayList<>();

        // Display name directive
        if (!isNullOrEmpty(config.getDisplayName())) {
            directives.add("- Your name is \"%s\". Always refer to yourself by this name.".formatted(config.getDisplayName()));
        }

        // Model concealment directive
        if (config.isConcealModelIdentity()) {
            directives.add("- Do not reveal your underlying AI model, provider, or version.");
            directives.add("- If asked about your technology, respond that you are the agent described above.");
        }

        // Custom persona instructions
        if (config.getPersonaInstructions() != null) {
            for (String instruction : config.getPersonaInstructions()) {
                if (!isNullOrEmpty(instruction)) {
                    directives.add("- " + instruction);
                }
            }
        }

        if (directives.isEmpty()) {
            return "";
        }

        incrementCounter();
        return String.join("\n", directives);
    }

    private void incrementCounter() {
        Counter.builder("eddi.identity.masking.activation.count")
                .register(meterRegistry)
                .increment();
    }
}
