/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link IntegrationTestProfile} plus the OpenAI-compatible adapter, which is
 * disabled by default and so would otherwise 404 for every request.
 * <p>
 * An API key is configured deliberately rather than left blank: the adapter's
 * authentication only engages when one is set, and an integration test that
 * skipped it would leave the whole auth path unexercised at the HTTP level.
 */
public class OpenAiCompatTestProfile extends IntegrationTestProfile {

    /** The shared secret this profile expects callers to present. */
    public static final String API_KEY = "sk-eddi-integration-test";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("eddi.openai-compat.enabled", "true");
        overrides.put("eddi.openai-compat.api-key", API_KEY);
        // Identity comes from X-OpenWebUI-User-Id, as it would from Open WebUI.
        overrides.put("eddi.openai-compat.trust-user-headers", "true");
        overrides.put("eddi.openai-compat.allow-anonymous", "false");
        return overrides;
    }
}
