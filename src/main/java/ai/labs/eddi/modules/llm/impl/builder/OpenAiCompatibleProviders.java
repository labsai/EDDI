/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The catalog of named OpenAI-compatible providers, loaded once from
 * {@code llm/openai-compatible-providers.json} on the classpath.
 * <p>
 * The Manager keeps a static mirror of this file; a parity test fails on any
 * drift between the two.
 */
public final class OpenAiCompatibleProviders {

    static final String RESOURCE = "/llm/openai-compatible-providers.json";

    private OpenAiCompatibleProviders() {
        // non-instantiable utility
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Catalog(List<OpenAiCompatibleProvider> providers) {
    }

    private static final class Holder {
        static final Map<String, OpenAiCompatibleProvider> PROVIDERS = load();
    }

    /** All providers, in catalog order. */
    public static List<OpenAiCompatibleProvider> all() {
        return List.copyOf(Holder.PROVIDERS.values());
    }

    /** The provider for an LLM task type; case- and whitespace-insensitive. */
    public static Optional<OpenAiCompatibleProvider> find(String type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(Holder.PROVIDERS.get(type.trim().toLowerCase(Locale.ROOT)));
    }

    public static boolean isCompatibleProvider(String type) {
        return find(type).isPresent();
    }

    static Map<String, OpenAiCompatibleProvider> load() {
        try (InputStream in = OpenAiCompatibleProviders.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            return validate(new ObjectMapper().readValue(in, Catalog.class).providers());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    static Map<String, OpenAiCompatibleProvider> validate(List<OpenAiCompatibleProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " declares no providers");
        }
        Map<String, OpenAiCompatibleProvider> byId = new LinkedHashMap<>();
        for (OpenAiCompatibleProvider p : providers) {
            if (p.id() == null || p.id().isBlank()) {
                throw new IllegalStateException("A provider in " + RESOURCE + " has no id");
            }
            if (!p.id().equals(p.id().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Provider id must be lower-case: " + p.id());
            }
            requireText(p.id(), "defaultBaseUrl", p.defaultBaseUrl());
            requireText(p.id(), "defaultModel", p.defaultModel());
            requireHttps(p.id(), p.defaultBaseUrl());
            for (OpenAiCompatibleProvider.Region region : p.regions()) {
                requireText(p.id(), "region id", region.id());
                requireHttps(p.id(), region.baseUrl());
            }
            if (byId.put(p.id(), p) != null) {
                throw new IllegalStateException("Duplicate provider id in " + RESOURCE + ": " + p.id());
            }
        }
        return byId;
    }

    private static void requireText(String id, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Provider '" + id + "' has a blank " + field);
        }
    }

    private static void requireHttps(String id, String url) {
        if (url == null || !url.startsWith("https://")) {
            throw new IllegalStateException("Provider '" + id + "' must use an https URL, got: " + url);
        }
    }
}
