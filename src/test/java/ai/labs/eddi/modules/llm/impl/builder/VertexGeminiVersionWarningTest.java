/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static ai.labs.eddi.modules.llm.impl.builder.VertexGeminiLanguageModelBuilder.isGemini3OrLater;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code gemini-vertex} Gemini 3.x warning.
 * <p>
 * Unlike the {@code gemini} provider, this one cannot be fixed inside EDDI:
 * neither {@code langchain4j-vertex-ai-gemini:1.20.0-beta30} nor the
 * {@code com.google.cloud.vertexai.api.Part} protobuf it depends on has a
 * {@code thought_signature} field, so Gemini 3.x tool calling is impossible on
 * this path until both change upstream. All EDDI can do is say so, rather than
 * letting the operator meet a bare {@code 400 INVALID_ARGUMENT} from deep
 * inside the provider.
 * <p>
 * What is worth testing is the version predicate, because the cost of getting
 * it wrong is asymmetric: a false positive tells operators to migrate off a
 * provider that works fine for them.
 */
@DisplayName("VertexGeminiLanguageModelBuilder — Gemini 3.x warning")
class VertexGeminiVersionWarningTest {

    @ParameterizedTest
    @ValueSource(strings = {"gemini-3-pro", "gemini-3.5-flash", "gemini-3.8-flash", "GEMINI-3-PRO",
            "gemini-4-flash", "gemini-10-pro", " gemini-3.8-flash ",
            "publishers/google/models/gemini-3-pro",
            "projects/my-project/locations/us-central1/publishers/google/models/gemini-3.5-flash"})
    @DisplayName("classifies Gemini 3 and later, bare or fully qualified, including a two-digit major version")
    void warnsForGemini3AndLater(String modelId) {
        assertTrue(isGemini3OrLater(modelId), modelId + " should be classified as Gemini 3 or later");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-pro", "gemini-2.5-pro",
            "text-bison", "my-tuned-endpoint", "gemini", "gemini-",
            "gemini-2.5-flash-lite", "gemini-embedding-001", "gemini-exp-1206", "gemini-live-2.5-flash",
            "publishers/google/models/gemini-2.5-pro"})
    @DisplayName("stays quiet for Gemini 2.x and anything it cannot classify")
    void quietForSupportedAndUnknownModels(String modelId) {
        assertFalse(isGemini3OrLater(modelId), modelId + " must not warn");
    }

    @Test
    @DisplayName("an unset model id is not classified")
    void nullIsQuiet() {
        assertFalse(isGemini3OrLater(null));
        assertFalse(isGemini3OrLater(""));
    }

    @Test
    @DisplayName("build() emits the warning for a Gemini 3.x model id")
    void buildWarnsForGemini3() {
        assertTrue(buildAndCaptureWarnings("gemini-3.8-flash").stream().anyMatch(m -> m.contains("gemini-3.8-flash")),
                "the predicate is only useful if build() actually calls it");
    }

    @Test
    @DisplayName("build() stays quiet for Gemini 2.x")
    void buildQuietForGemini2() {
        assertTrue(buildAndCaptureWarnings("gemini-2.5-flash").stream().noneMatch(m -> m.contains("thoughtSignature")));
    }

    /**
     * Drives the real {@code build()} and returns the WARNING messages it logged.
     * Building a real {@code VertexAiGeminiChatModel} needs GCP credentials, which
     * a unit run does not have, so the constructor may throw — after the warning,
     * which {@code build()} emits before constructing anything. That is what lets
     * this pin the wiring without credentials.
     */
    private static List<String> buildAndCaptureWarnings(String modelId) {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() < Level.WARNING.intValue()) {
                    return;
                }
                // The printf template and its parameters travel separately.
                StringBuilder text = new StringBuilder(String.valueOf(record.getMessage()));
                if (record.getParameters() != null) {
                    for (Object parameter : record.getParameters()) {
                        text.append(' ').append(parameter);
                    }
                }
                warnings.add(text.toString());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // logging.properties turns the ai.labs.eddi namespace OFF for plain unit tests,
        // so the logger has to be opened explicitly.
        Logger logger = Logger.getLogger(VertexGeminiLanguageModelBuilder.class.getName());
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            new VertexGeminiLanguageModelBuilder()
                    .build(Map.of("modelId", modelId, "projectId", "test-project", "location", "us-central1"));
        } catch (RuntimeException expectedWithoutCredentials) {
            // see method Javadoc
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previousLevel);
        }
        return warnings;
    }
}
