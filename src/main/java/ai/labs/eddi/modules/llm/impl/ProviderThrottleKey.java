/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Names the provider quota a call counts against:
 * {@code provider|endpoint host|model|credential scope}.
 *
 * <p>
 * Quotas are scoped per project and model (Gemini's are "per user per project
 * per model"), so two knowledge bases on the same key and model share one
 * cooldown, while a different model or project does not. The credential scope
 * is the project when the configuration names one ({@code project} for Vertex,
 * or {@code quotaProject}), and otherwise a truncated SHA-256 of the resolved
 * key — so the key itself never appears in a row, a log line or a metric tag.
 */
public final class ProviderThrottleKey {

    /** Hex characters of the credential hash kept: enough to tell keys apart. */
    private static final int SCOPE_HASH_LENGTH = 16;

    private ProviderThrottleKey() {
    }

    /**
     * The key for a provider configured with {@code params} — the resolved
     * parameters, secrets included, so that two vault references to one key share a
     * quota.
     */
    public static String of(String provider, Map<String, String> params) {
        Map<String, String> safe = params == null ? Map.of() : params;
        return String.join("|",
                String.valueOf(provider).toLowerCase(Locale.ROOT),
                host(safe),
                firstPresent(safe, "model", "modelName", "deploymentName", "modelId"),
                scope(safe));
    }

    private static String host(Map<String, String> params) {
        String endpoint = firstPresent(params, "baseUrl", "endpoint", "location", "region");
        if (endpoint.isEmpty()) {
            return "default";
        }
        try {
            String host = URI.create(endpoint).getHost();
            return host == null ? endpoint.toLowerCase(Locale.ROOT) : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return endpoint.toLowerCase(Locale.ROOT);
        }
    }

    private static String scope(Map<String, String> params) {
        String project = firstPresent(params, "quotaProject", "project", "projectId");
        if (!project.isEmpty()) {
            return "project:" + project;
        }
        String credential = firstPresent(params, "apiKey", "accessKeyId");
        if (credential.isEmpty()) {
            return "none";
        }
        return "key:" + sha256(credential).substring(0, SCOPE_HASH_LENGTH);
    }

    private static String firstPresent(Map<String, String> params, String... names) {
        for (String name : names) {
            String value = params.get(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
