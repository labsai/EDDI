/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.modules.llm.bootstrap.LlmModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAiCompatibleProviders catalog")
class OpenAiCompatibleProvidersTest {

    @Nested
    @DisplayName("shipped catalog")
    class ShippedCatalog {

        @Test
        @DisplayName("loads the eight named providers")
        void loads() {
            Set<String> ids = new HashSet<>();
            OpenAiCompatibleProviders.all().forEach(p -> ids.add(p.id()));
            assertEquals(Set.of("xai", "deepseek", "moonshot", "qwen", "zhipu", "minimax", "openrouter", "groq"), ids);
        }

        @Test
        @DisplayName("ids are lower-case and never collide with a built-in LLM type")
        void idsDoNotCollide() throws IllegalAccessException {
            Set<String> builtIn = new HashSet<>();
            for (Field field : LlmModule.class.getDeclaredFields()) {
                if (field.getName().startsWith("LLM_TYPE_") && Modifier.isStatic(field.getModifiers())) {
                    builtIn.add((String) field.get(null));
                }
            }
            assertFalse(builtIn.isEmpty());
            for (OpenAiCompatibleProvider p : OpenAiCompatibleProviders.all()) {
                assertEquals(p.id().toLowerCase(Locale.ROOT), p.id());
                assertFalse(builtIn.contains(p.id()), p.id() + " collides with a built-in type");
            }
        }

        @Test
        @DisplayName("every URL is https, every default model is suggested, region ids are unique")
        void entriesAreConsistent() {
            for (OpenAiCompatibleProvider p : OpenAiCompatibleProviders.all()) {
                assertTrue(p.defaultBaseUrl().startsWith("https://"), p.id());
                assertTrue(p.modelSuggestions().contains(p.defaultModel()), p.id() + " default model not in suggestions");
                Set<String> regionIds = new HashSet<>();
                for (OpenAiCompatibleProvider.Region r : p.regions()) {
                    assertTrue(r.baseUrl().startsWith("https://"), p.id() + "/" + r.id());
                    assertTrue(regionIds.add(r.id()), p.id() + " repeats region " + r.id());
                }
                if (!p.regions().isEmpty()) {
                    assertTrue(p.regions().stream().anyMatch(r -> r.baseUrl().equals(p.defaultBaseUrl())),
                            p.id() + " default endpoint is not one of its regions");
                }
            }
        }

        @Test
        @DisplayName("find is case-, whitespace- and null-safe")
        void find() {
            assertTrue(OpenAiCompatibleProviders.find("  XAI ").isPresent());
            assertTrue(OpenAiCompatibleProviders.find(null).isEmpty());
            assertTrue(OpenAiCompatibleProviders.find("openai").isEmpty());
            assertTrue(OpenAiCompatibleProviders.find("deepseek").isPresent());
        }

        @Test
        @DisplayName("no vision token matches a known text-only model id")
        void visionTokensNeverMatchTextOnlyModels() {
            List<String> textOnly = List.of("qwen3.7-max", "glm-5.3", "glm-5.2", "deepseek-v4-pro", "MiniMax-M2.7", "openai/gpt-oss-120b");
            for (OpenAiCompatibleProvider p : OpenAiCompatibleProviders.all()) {
                for (String token : p.capabilities().visionModelTokens()) {
                    for (String model : textOnly) {
                        assertFalse(model.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT)),
                                p.id() + " vision token '" + token + "' falsely matches text-only model " + model);
                    }
                }
            }
        }

        @Test
        @DisplayName("deepseek echoes reasoning back; minimax splits reasoning")
        void quirks() {
            var deepseek = OpenAiCompatibleProviders.find("deepseek").orElseThrow();
            assertEquals("true", deepseek.parameterDefaults().get("returnThinking"));
            assertEquals("true", deepseek.parameterDefaults().get("sendThinking"));
            var minimax = OpenAiCompatibleProviders.find("minimax").orElseThrow();
            assertEquals(true, minimax.customParameters().get("reasoning_split"));
            // reasoning_split puts thinking into reasoning_content, which MiniMax wants
            // appended back to the history.
            assertEquals("true", minimax.parameterDefaults().get("returnThinking"));
            assertEquals("true", minimax.parameterDefaults().get("sendThinking"));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        private OpenAiCompatibleProvider provider(String id, String baseUrl, String model) {
            return new OpenAiCompatibleProvider(id, id, baseUrl, List.of(), model, List.of(model), null, null, null, null, null);
        }

        @Test
        @DisplayName("rejects a duplicate id")
        void duplicate() {
            var p = provider("a", "https://a.example", "m");
            assertThrows(IllegalStateException.class, () -> OpenAiCompatibleProviders.validate(List.of(p, p)));
        }

        @Test
        @DisplayName("rejects blank base URL, blank model and non-https URL")
        void invalid() {
            assertThrows(IllegalStateException.class, () -> OpenAiCompatibleProviders.validate(List.of(provider("a", " ", "m"))));
            assertThrows(IllegalStateException.class, () -> OpenAiCompatibleProviders.validate(List.of(provider("a", "https://a.example", ""))));
            assertThrows(IllegalStateException.class, () -> OpenAiCompatibleProviders.validate(List.of(provider("a", "http://a.example", "m"))));
        }

        @Test
        @DisplayName("rejects a null key or value in the parameter maps, naming the provider and field")
        void nullParameterEntries() {
            var nullValue = new HashMap<String, String>();
            nullValue.put("sendThinking", null);
            var e = assertThrows(IllegalStateException.class,
                    () -> new OpenAiCompatibleProvider("p", "P", "https://a.example", List.of(), "m", List.of("m"), null, null, nullValue, null,
                            null));
            assertTrue(e.getMessage().contains("'p'") && e.getMessage().contains("parameterDefaults"), e.getMessage());

            var nullCustom = new HashMap<String, Object>();
            nullCustom.put("reasoning_split", null);
            var e2 = assertThrows(IllegalStateException.class,
                    () -> new OpenAiCompatibleProvider("p", "P", "https://a.example", List.of(), "m", List.of("m"), null, null, null, nullCustom,
                            null));
            assertTrue(e2.getMessage().contains("customParameters"), e2.getMessage());
        }

        @Test
        @DisplayName("the catalog tolerates unknown JSON properties")
        void ignoresUnknownProperties() throws Exception {
            String json = "{\"id\":\"a\",\"extra\":1,\"defaultBaseUrl\":\"https://a.example\",\"defaultModel\":\"m\","
                    + "\"regions\":[{\"id\":\"r\",\"baseUrl\":\"https://r.example\",\"note\":\"x\"}],"
                    + "\"capabilities\":{\"jsonMode\":true,\"future\":1}}";
            var p = new ObjectMapper().readValue(json, OpenAiCompatibleProvider.class);
            assertEquals("a", p.id());
            assertEquals(1, p.regions().size());
            assertTrue(p.capabilities().jsonMode());
        }

        @Test
        @DisplayName("rejects an upper-case id")
        void upperCase() {
            assertThrows(IllegalStateException.class, () -> OpenAiCompatibleProviders.validate(List.of(provider("A", "https://a.example", "m"))));
        }
    }
}
