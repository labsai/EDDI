/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chart's white-labeling values (eddi.branding.*): off by default, and
 * every seed property the backend reads ({@code BrandingConfig}) has its
 * environment variable in the ConfigMap, rendered only when set. CI's
 * manifest-lint job renders the chart itself; this keeps the mapping honest
 * without helm.
 */
class HelmBrandingValuesTest {

    private static final Path CHART = Path.of("helm", "eddi");

    /**
     * Every eddi.branding.* property, as BrandingConfig maps it, in env-var form.
     */
    private static final List<String> ENV = List.of(
            "EDDI_BRANDING_PRODUCT_NAME", "EDDI_BRANDING_SHORT_NAME", "EDDI_BRANDING_TAGLINE",
            "EDDI_BRANDING_COLOR_PRIMARY", "EDDI_BRANDING_COLOR_PRIMARY_FOREGROUND", "EDDI_BRANDING_COLOR_DARK_PRIMARY",
            "EDDI_BRANDING_COLOR_DARK_PRIMARY_FOREGROUND", "EDDI_BRANDING_COLOR_CHAT_ACCENT", "EDDI_BRANDING_POWERED_BY",
            "EDDI_BRANDING_SHOW_UPDATE_CHECK", "EDDI_BRANDING_LOCKED", "EDDI_BRANDING_ASSETS_DIR");

    @Test
    void everythingIsOffByDefault() throws IOException {
        JsonNode branding = new ObjectMapper(new YAMLFactory()).readTree(CHART.resolve("values.yaml").toFile()).path("eddi").path("branding");

        assertFalse(branding.isMissingNode(), "eddi.branding is documented in values.yaml");
        for (String field : List.of("productName", "shortName", "tagline")) {
            assertEquals("", branding.path(field).asText("x"), field);
        }
        branding.path("colors").forEach(color -> assertEquals("", color.asText("x")));
        assertTrue(branding.path("poweredBy").isNull());
        assertTrue(branding.path("showUpdateCheck").isNull());
        assertFalse(branding.path("locked").asBoolean(true));
        assertEquals("", branding.path("assets").path("configMap").asText("x"));
    }

    @Test
    void everySeedPropertyIsRenderedOnlyWhenSet() throws IOException {
        String configMap = Files.readString(CHART.resolve("templates/configmap.yaml"));
        for (String env : ENV) {
            int at = configMap.indexOf("  " + env + ":");
            assertTrue(at > 0, env + " is rendered by configmap.yaml");
            // The line before it opens the guard ({{- with … }} or {{- if … }}).
            String before = configMap.substring(configMap.lastIndexOf('\n', configMap.lastIndexOf('\n', at - 1) - 1), at);
            assertTrue(before.contains("{{- with") || before.contains("{{- if"), env + " is guarded: " + before);
        }
    }

    @Test
    void theImagesAreMountedReadOnlyOnlyWithAConfigMap() throws IOException {
        String deployment = Files.readString(CHART.resolve("templates/deployment.yaml"));
        int mount = deployment.indexOf("- name: branding-assets\n              mountPath:");
        if (mount < 0) {
            mount = deployment.indexOf("- name: branding-assets\r\n              mountPath:");
        }
        assertTrue(mount > 0, "the eddi container mounts branding-assets");
        String block = deployment.substring(deployment.lastIndexOf("{{- if $brandAssets.configMap }}", mount), mount + 200);
        assertTrue(block.contains("readOnly: true"), block);
        assertTrue(deployment.contains("{{- with ((.Values.eddi.branding | default dict).assets | default dict).configMap }}"));
    }
}
