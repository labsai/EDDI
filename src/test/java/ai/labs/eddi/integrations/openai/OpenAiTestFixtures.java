/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.model.Deployment.Environment;

/**
 * Shared fixtures for the OpenAI adapter tests.
 * <p>
 * Agent ids here are <b>hex and at least 18 characters</b> on purpose: EDDI's
 * id validation rejects shorter or non-hex ids, which silently yields a null id
 * and makes assertions pass for the wrong reason.
 */
final class OpenAiTestFixtures {

    static final String AGENT_ID_SUPPORT = "66a1b2c3d4e5f60718a3f9c1";
    static final String AGENT_ID_SALES = "66a1b2c3d4e5f60718b4e2d7";
    static final String AGENT_ID_OTHER = "66a1b2c3d4e5f60718c5d3e8";

    private OpenAiTestFixtures() {
    }

    /** A config with the adapter enabled and everything else at its default. */
    static OpenAiCompatConfig enabledConfig() {
        return config(builder -> {
        });
    }

    static OpenAiCompatConfig config(java.util.function.Consumer<ConfigBuilder> customizer) {
        ConfigBuilder builder = new ConfigBuilder();
        customizer.accept(builder);
        return builder.build();
    }

    /** Mutable stand-in for the injected configuration. */
    static final class ConfigBuilder {
        boolean enabled = true;
        String apiKey = null;
        String httpPolicy = OpenAiCompatConfig.POLICY_PERMIT;
        boolean trustUserHeaders = true;
        boolean allowAnonymous = false;
        String defaultUser = "openai-anonymous";
        Environment environment = Environment.production;
        int requestTimeoutSeconds = 120;
        int maxConcurrentRequests = 64;
        int modelCacheSeconds = 30;
        boolean exposeStatelessVariants = true;

        OpenAiCompatConfig build() {
            return new OpenAiCompatConfig(enabled, java.util.Optional.ofNullable(apiKey), httpPolicy,
                    trustUserHeaders, allowAnonymous, defaultUser, environment, requestTimeoutSeconds,
                    maxConcurrentRequests, modelCacheSeconds, exposeStatelessVariants);
        }
    }
}
