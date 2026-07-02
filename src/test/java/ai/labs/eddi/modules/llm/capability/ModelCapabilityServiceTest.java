/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.capability;

import ai.labs.eddi.modules.llm.capability.ModelCapabilityService.Capability;
import ai.labs.eddi.modules.llm.capability.ModelCapabilityService.Support;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ModelCapabilityService}.
 */
class ModelCapabilityServiceTest {

    private final Map<String, String> config = new HashMap<>();
    private final Function<String, Optional<String>> lookup = key -> Optional.ofNullable(config.get(key));
    private final ModelCapabilityService service = new ModelCapabilityService(lookup);

    // ==================== Vision defaults ====================

    @Nested
    class VisionDefaults {

        @ParameterizedTest
        @CsvSource({
                "openai,gpt-4o,true",
                "openai,gpt-4.1,true",
                "openai,gpt-3.5-turbo,false",
                "openai,text-embedding-3-small,false",
                "azure-openai,gpt-4o,true",
                "anthropic,claude-sonnet-4,true",
                "gemini,gemini-2.0-flash,true",
                "gemini-vertex,gemini-1.5-pro,true",
                "mistral,pixtral-12b,true",
                "mistral,mistral-embed,false",
                "mistral,mixtral-8x7b,false",
                "ollama,llava,true",
                "ollama,llama3.2-vision,true",
                "ollama,llama3,false",
                "bedrock,amazon.nova-pro-v1,true",
                "bedrock,anthropic.claude-3-sonnet,true",
                "bedrock,amazon.titan-text,false",
                "oracle-genai,meta.llama-3.2-90b-vision,true",
                "jlama,tjake/llama,false",
                "huggingface,any-model,false",
                "unknown-provider,some-model,false"})
        void visionDefaults(String provider, String model, boolean expected) {
            assertEquals(expected, service.supportsVision(provider, model),
                    provider + "/" + model + " vision should be " + expected);
        }

        @Test
        void blankProviderIsUnsupported() {
            assertFalse(service.supportsVision("", "gpt-4o"));
            assertFalse(service.supportsVision(null, "gpt-4o"));
        }

        @Test
        void blankModelUsesProviderDefault() {
            assertTrue(service.supportsVision("openai", ""));
            assertTrue(service.supportsVision("openai", null));
            assertFalse(service.supportsVision("ollama", "")); // model-dependent → off without a known vision model
        }
    }

    // ==================== Documents defaults ====================

    @Nested
    class DocumentDefaults {

        @ParameterizedTest
        @CsvSource({
                "anthropic,claude-sonnet-4,true",
                "anthropic,claude-3-opus,true",
                "anthropic,claude-2.1,false",
                "anthropic,claude-instant-1,false",
                "gemini,gemini-2.0-flash,true",
                "gemini-vertex,gemini-1.5-pro,true",
                "openai,gpt-4o,false",
                "azure-openai,gpt-4o,false",
                "mistral,pixtral-12b,false",
                "ollama,llava,false",
                "bedrock,anthropic.claude-3,false",
                "jlama,x,false"})
        void documentDefaults(String provider, String model, boolean expected) {
            assertEquals(expected, service.supportsDocuments(provider, model),
                    provider + "/" + model + " documents should be " + expected);
        }
    }

    // ==================== Audio defaults ====================

    @Nested
    class AudioDefaults {

        @ParameterizedTest
        @CsvSource({
                "gemini,gemini-2.0-flash,true",
                "gemini-vertex,gemini-1.5-pro,true",
                "openai,gpt-4o,false",
                "anthropic,claude-sonnet-4,false",
                "ollama,llava,false"})
        void audioDefaults(String provider, String model, boolean expected) {
            assertEquals(expected, service.supportsAudio(provider, model));
        }
    }

    // ==================== Image-by-URL defaults ====================

    @Nested
    class ImageUrlDefaults {

        @ParameterizedTest
        @CsvSource({
                "openai,gpt-4o,true",
                "azure-openai,gpt-4o,true",
                "anthropic,claude-sonnet-4,false",
                "gemini,gemini-2.0-flash,false",
                "mistral,pixtral-12b,false",
                "ollama,llava,false"})
        void imageUrlDefaults(String provider, String model, boolean expected) {
            assertEquals(expected, service.supportsImageUrl(provider, model));
        }
    }

    // ==================== Task overrides ====================

    @Nested
    class TaskOverrides {

        @Test
        void onForcesTrueEvenWhenDefaultFalse() {
            assertFalse(service.supportsVision("jlama", "x"));
            assertTrue(service.supportsVision("jlama", "x", Support.ON));
        }

        @Test
        void offForcesFalseEvenWhenDefaultTrue() {
            assertTrue(service.supportsVision("openai", "gpt-4o"));
            assertFalse(service.supportsVision("openai", "gpt-4o", Support.OFF));
        }

        @Test
        void autoFallsThroughToDefault() {
            assertTrue(service.supportsVision("openai", "gpt-4o", Support.AUTO));
            assertFalse(service.supportsVision("jlama", "x", Support.AUTO));
        }
    }

    // ==================== Deployment overrides ====================

    @Nested
    class DeploymentOverrides {

        @Test
        void providerSpecificOverrideEnables() {
            assertFalse(service.supportsVision("ollama", "llama3")); // default off
            config.put("eddi.multimodal.ollama.vision", "on");
            assertTrue(service.supportsVision("ollama", "llama3"));
        }

        @Test
        void providerSpecificOverrideDisables() {
            assertTrue(service.supportsVision("openai", "gpt-4o")); // default on
            config.put("eddi.multimodal.openai.vision", "off");
            assertFalse(service.supportsVision("openai", "gpt-4o"));
        }

        @Test
        void globalOverrideAppliesToAllProviders() {
            config.put("eddi.multimodal.vision", "off");
            assertFalse(service.supportsVision("openai", "gpt-4o"));
            assertFalse(service.supportsVision("anthropic", "claude-sonnet-4"));
        }

        @Test
        void providerSpecificTakesPrecedenceOverGlobal() {
            config.put("eddi.multimodal.vision", "off");
            config.put("eddi.multimodal.openai.vision", "on");
            assertTrue(service.supportsVision("openai", "gpt-4o"));
            assertFalse(service.supportsVision("anthropic", "claude-sonnet-4"));
        }

        @Test
        void taskOverrideBeatsDeploymentOverride() {
            config.put("eddi.multimodal.openai.vision", "off");
            assertTrue(service.supportsVision("openai", "gpt-4o", Support.ON));
        }

        @Test
        void autoValuedOverrideFallsThrough() {
            config.put("eddi.multimodal.openai.vision", "auto");
            assertTrue(service.supportsVision("openai", "gpt-4o"));
        }

        @Test
        void documentsAndAudioOverridable() {
            config.put("eddi.multimodal.openai.documents", "on");
            assertTrue(service.supportsDocuments("openai", "gpt-4o"));
            config.put("eddi.multimodal.audio", "on");
            assertTrue(service.supportsAudio("anthropic", "claude-sonnet-4"));
        }
    }

    // ==================== Support.parse ====================

    @Nested
    class SupportParsing {

        @ParameterizedTest
        @ValueSource(strings = {"on", "true", "yes", "enabled", "ON", "True"})
        void parsesOn(String token) {
            assertEquals(Support.ON, Support.parse(token));
        }

        @ParameterizedTest
        @ValueSource(strings = {"off", "false", "no", "disabled", "OFF"})
        void parsesOff(String token) {
            assertEquals(Support.OFF, Support.parse(token));
        }

        @ParameterizedTest
        @ValueSource(strings = {"auto", "maybe", "", "garbage"})
        void parsesAuto(String token) {
            assertEquals(Support.AUTO, Support.parse(token));
        }

        @Test
        void parsesNullAsAuto() {
            assertEquals(Support.AUTO, Support.parse(null));
        }
    }

    // ==================== Generic supports() + capability suffixes
    // ====================

    @Test
    void genericSupportsMatchesConvenienceMethods() {
        assertEquals(service.supportsVision("openai", "gpt-4o"),
                service.supports(Capability.VISION, "openai", "gpt-4o", Support.AUTO));
        assertEquals(service.supportsImageUrl("openai", "gpt-4o"),
                service.supports(Capability.IMAGE_URL, "openai", "gpt-4o", Support.AUTO));
    }

    @Test
    void capabilityConfigSuffixes() {
        assertEquals("vision", Capability.VISION.configSuffix());
        assertEquals("documents", Capability.DOCUMENTS.configSuffix());
        assertEquals("audio", Capability.AUDIO.configSuffix());
        assertEquals("image-url", Capability.IMAGE_URL.configSuffix());
    }
}
