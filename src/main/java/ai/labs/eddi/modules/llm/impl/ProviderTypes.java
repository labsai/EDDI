/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Resolves an LLM provider type for a model build — the run-time backstop of
 * {@code LlmProviderAuthorPolicy}.
 * <p>
 * A task's, cascade step's and judge's {@code type} may be a
 * {@code ${vars:...}} reference, expanded here. The authoring policy refuses a
 * non-literal type from a non-administrator, but a stored config predating it,
 * or a variable changed afterwards, could still steer such a type onto a
 * provider that authenticates with the server's own cloud identity. No
 * deployment needs a variable to select one, so a type that only reaches a
 * restricted provider ({@code eddi.llm.restricted-providers}) through a
 * variable is not resolved: the unresolved reference is returned, and the
 * registry refuses it as an unsupported type — the failure path every caller
 * already handles. A literal restricted type is unaffected.
 */
public final class ProviderTypes {

    private static final Logger LOGGER = Logger.getLogger(ProviderTypes.class);

    static final String RESTRICTED_PROVIDERS_PROPERTY = "eddi.llm.restricted-providers";
    static final String DEFAULT_RESTRICTED_PROVIDERS = "bedrock,gemini-vertex,oracle-genai,jlama";

    private ProviderTypes() {
    }

    /**
     * {@code raw} with global variables resolved, unless that selects a restricted
     * provider.
     */
    static String resolve(GlobalVariableResolver resolver, String raw) {
        String resolved = resolver.resolveValue(raw);
        if (raw != null && resolved != null && !raw.equals(resolved) && isRestricted(resolved)) {
            LOGGER.warnf("LLM provider type '%s' resolves through a variable to the restricted provider '%s' — refused; "
                    + "name the provider literally", sanitize(raw), sanitize(resolved));
            return raw;
        }
        return resolved;
    }

    /** Whether {@code type}, trimmed and lower-cased, is a restricted provider. */
    public static boolean isRestricted(String type) {
        return type != null && restricted().contains(type.trim().toLowerCase(Locale.ROOT));
    }

    static Set<String> restricted() {
        String configured;
        try {
            configured = ConfigProvider.getConfig().getOptionalValue(RESTRICTED_PROVIDERS_PROPERTY, String.class)
                    .orElse(DEFAULT_RESTRICTED_PROVIDERS);
        } catch (RuntimeException e) {
            configured = DEFAULT_RESTRICTED_PROVIDERS;
        }
        return Arrays.stream(configured.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
