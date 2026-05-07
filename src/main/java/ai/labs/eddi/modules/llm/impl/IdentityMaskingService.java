/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration.IdentityMaskingConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Prepends identity masking rules to the system prompt. When enabled, masking
 * rules are injected regardless of counterweight settings.
 * <p>
 * The audit ledger records {@code identityMaskingActive=true} for every
 * conversation turn this runs (handled by the caller in {@code LlmTask}).
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class IdentityMaskingService {

    private static final Logger LOGGER = Logger.getLogger(IdentityMaskingService.class);

    static final String DEFAULT_MASKING_HEADER = "## IDENTITY POLICY (engine-enforced)";

    private final MeterRegistry meterRegistry;
    private Counter maskingAppliedCounter;

    @Inject
    public IdentityMaskingService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        maskingAppliedCounter = meterRegistry.counter("eddi.identity.masking.applied");
    }

    /**
     * Apply identity masking rules to the system message.
     *
     * @param systemMessage
     *            the current system message
     * @param config
     *            identity masking config from agent configuration (may be null)
     * @return the (possibly modified) system message with masking rules prepended
     */
    public String apply(String systemMessage, IdentityMaskingConfig config) {
        if (config == null || !config.isEnabled()) {
            return systemMessage;
        }

        List<String> rules = config.getRules();
        if (rules == null || rules.isEmpty()) {
            return systemMessage;
        }

        StringBuilder sb = new StringBuilder(DEFAULT_MASKING_HEADER).append("\n");
        for (String rule : rules) {
            sb.append("- ").append(rule).append("\n");
        }

        maskingAppliedCounter.increment();
        LOGGER.debugf("Identity masking applied with %d rule(s)", rules.size());

        return sb.toString().trim() + "\n\n" + systemMessage;
    }
}
