/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;

import java.util.Set;

/**
 * A test builder that, like the real providers, takes its credential in
 * {@code apiKey} — the only key {@link ChatModelRegistry} then lets a
 * {@code ${vault:…}} reference sit in.
 */
interface ApiKeyLanguageModelBuilder extends ILanguageModelBuilder {

    @Override
    default Set<String> secretParameters() {
        return Set.of("apiKey");
    }
}
