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

        @ParameterizedTest
        @ValueSource(strings = {
                "llava", "bakllava", "some-vision-model", "pixtral-12b",
                "anthropic.claude-3-haiku", "anthropic.claude-opus", "anthropic.claude-sonnet",
                "amazon.nova-lite", "amazon.nova-pro", "amazon.nova-premier",
                "meta.llama3.2-vision", "meta.llama-3.2-90b", "meta.llama3-2-11b",
                "gemma3-27b", "gemma-3-12b", "qwen2-vl-7b", "qwen2.5-vl-7b",
                "minicpm-v-2.6", "moondream2"})
        void modelDependentProviderUpgradesForKnownVisionModels(String model) {
            // bedrock is model-dependent → these known vision models flip it on
            assertTrue(service.supportsVision("bedrock", model),
                    "bedrock/" + model + " should be vision-capable");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "gpt-3.5-turbo", "text-davinci-003", "davinci-002", "babbage-002",
                "text-embedding-3-large", "some-embed-model", "embed-english-v3",
                "text-moderation-latest", "mistral-embed", "mistral-7b-instruct", "mixtral-8x22b"})
        void visionFirstProviderDowngradesForKnownTextOnlyModels(String model) {
            // openai/mistral are vision-first → these text-only models flip vision off
            String provider = model.startsWith("mistral") || model.startsWith("mixtral") ? "mistral" : "openai";
            assertFalse(service.supportsVision(provider, model),
                    provider + "/" + model + " should not be vision-capable");
        }

        @Test
        void blankProviderIsUnsupported() {
            assertFalse(service.supportsVision("", "gpt-4o"));
            assertFalse(service.supportsVision(null, "gpt-4o"));
        }

        @Test
        void blankModelIsUnsupported() {
            // A blank model name carries no evidence of multimodality, so the table must
            // fail closed rather than assume the provider's flagship.
            assertFalse(service.supportsVision("openai", ""));
            assertFalse(service.supportsVision("openai", null));
            assertFalse(service.supportsVision("ollama", ""));
        }
    }

    /**
     * The built-in table is an allow-list: a model must be <em>recognised</em> to
     * be considered capable.
     * <p>
     * It used to be a deny-list for the six "vision-first" providers
     * ({@code !isKnownTextOnlyModel(model)}), which inverted the class contract —
     * any model name the table did not know (a new release, a fine-tune, a
     * provider-side alias) was declared multimodal, the attachment was forwarded,
     * and the provider answered 400. These cases fail if that inversion returns.
     */
    @Nested
    class UnknownModelsFailClosed {

        @ParameterizedTest
        @CsvSource({
                "openai,some-brand-new-model",
                "openai,gpt-4",
                "azure-openai,my-custom-deployment",
                "anthropic,claude-2.1",
                // deliberately shares no family token with any table entry — a name like
                // "not-a-claude-at-all" would MATCH, because family detection is a
                // substring test ("anthropic.claude-3-sonnet" on Bedrock, and similar)
                "anthropic,mystery-model-v9",
                "gemini,palm-2",
                "gemini-vertex,text-bison",
                "mistral,mistral-large-latest",
                "ollama,phi4",
                "bedrock,amazon.titan-text-express-v1"})
        void unknownModelHasNoVision(String provider, String model) {
            assertFalse(service.supportsVision(provider, model),
                    provider + "/" + model + " is not in the capability table and must fail closed");
        }

        @Test
        void unknownModelHasNoImageUrl() {
            assertFalse(service.supportsImageUrl("openai", "some-brand-new-model"));
            assertFalse(service.supportsImageUrl("azure-openai", "my-custom-deployment"));
        }

        @Test
        void unknownModelHasNoNativeDocuments() {
            assertFalse(service.supportsDocuments("anthropic", "mystery-model-v9"));
            assertFalse(service.supportsDocuments("gemini", "palm-2"));
        }

        /**
         * Family detection is a deliberate substring test, so a name that embeds a
         * known token IS recognised even if it reads like a non-match to a human.
         * Pinned so the substring semantics are a stated contract rather than a
         * surprise the next time someone writes a fixture called "not-a-claude".
         */
        @Test
        void aNameEmbeddingAKnownFamilyTokenIsTreatedAsThatFamily() {
            assertTrue(service.supportsVision("anthropic", "not-a-claude-at-all"));
        }

        @Test
        void unknownModelHasNoAudio() {
            assertFalse(service.supportsAudio("gemini", "palm-2"));
        }

        @Test
        void anAgentDesignerCanStillAssertTheCapabilityExplicitly() {
            // The escape hatch for a working deployment whose model simply is not in the
            // table yet: per task…
            assertTrue(service.supportsVision("openai", "some-brand-new-model", Support.ON));
            // …or per deployment.
            config.put("eddi.multimodal.openai.vision", "on");
            assertTrue(service.supportsVision("openai", "some-brand-new-model"));
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
