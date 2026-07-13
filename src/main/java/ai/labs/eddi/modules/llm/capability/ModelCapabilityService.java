/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.capability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.*;

/**
 * Resolves whether a given {@code (provider, model)} pair supports a multimodal
 * capability (vision, native documents, audio, image-by-URL) before the
 * attachment forwarder hands content to the provider.
 * <p>
 * Resolution precedence, highest first:
 * <ol>
 * <li><b>Per-task override</b> — {@link Support#ON}/{@link Support#OFF} from
 * the agent's {@code LlmConfiguration.Task.multimodal} block;
 * {@link Support#AUTO} falls through.</li>
 * <li><b>Deployment override</b> —
 * {@code eddi.multimodal.<provider>.<capability>} then the global
 * {@code eddi.multimodal.<capability>} (each {@code on|off|auto}).</li>
 * <li><b>Built-in defaults</b> — the conservative, model-aware table
 * below.</li>
 * </ol>
 * Unknown providers/models resolve to <em>unsupported</em>, so the forwarder
 * falls back to text extraction or a metadata note and never sends content that
 * would error the provider. Capability is model-level; the defaults are
 * deliberately cautious and should be verified against the langchain4j release
 * in use.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class ModelCapabilityService {

    /** A multimodal capability that a provider/model may or may not support. */
    public enum Capability {
        VISION("vision"), DOCUMENTS("documents"), AUDIO("audio"), IMAGE_URL("image-url");

        private final String configSuffix;

        Capability(String configSuffix) {
            this.configSuffix = configSuffix;
        }

        public String configSuffix() {
            return configSuffix;
        }
    }

    /** Tri-state override for a capability. */
    public enum Support {
        AUTO, ON, OFF;

        /**
         * Parse a config/override token. {@code on|true|yes|enabled} → ON,
         * {@code off|false|no|disabled} → OFF, everything else (incl. null) → AUTO.
         */
        public static Support parse(String token) {
            if (token == null) {
                return AUTO;
            }
            return switch (token.trim().toLowerCase(Locale.ROOT)) {
                case "on", "true", "yes", "enabled", "enable" -> ON;
                case "off", "false", "no", "disabled", "disable" -> OFF;
                default -> AUTO;
            };
        }
    }

    private static final String CONFIG_PREFIX = "eddi.multimodal.";

    private final Function<String, Optional<String>> configLookup;

    @Inject
    public ModelCapabilityService(Config config) {
        this(key -> config.getOptionalValue(key, String.class));
    }

    /**
     * Programmatic constructor for tests / non-CDI callers.
     *
     * @param configLookup
     *            resolves a config key to its value (empty when unset)
     */
    public ModelCapabilityService(Function<String, Optional<String>> configLookup) {
        this.configLookup = configLookup;
    }

    public boolean supportsVision(String provider, String model) {
        return supports(Capability.VISION, provider, model, Support.AUTO);
    }

    public boolean supportsVision(String provider, String model, Support taskOverride) {
        return supports(Capability.VISION, provider, model, taskOverride);
    }

    public boolean supportsDocuments(String provider, String model) {
        return supports(Capability.DOCUMENTS, provider, model, Support.AUTO);
    }

    public boolean supportsDocuments(String provider, String model, Support taskOverride) {
        return supports(Capability.DOCUMENTS, provider, model, taskOverride);
    }

    public boolean supportsAudio(String provider, String model) {
        return supports(Capability.AUDIO, provider, model, Support.AUTO);
    }

    public boolean supportsAudio(String provider, String model, Support taskOverride) {
        return supports(Capability.AUDIO, provider, model, taskOverride);
    }

    public boolean supportsImageUrl(String provider, String model) {
        return supports(Capability.IMAGE_URL, provider, model, Support.AUTO);
    }

    public boolean supportsImageUrl(String provider, String model, Support taskOverride) {
        return supports(Capability.IMAGE_URL, provider, model, taskOverride);
    }

    /**
     * Resolve a capability applying the full precedence chain.
     *
     * @param capability
     *            the capability in question
     * @param provider
     *            the LLM provider type (e.g. {@code openai}, {@code anthropic})
     * @param model
     *            the resolved model name (may be null/blank)
     * @param taskOverride
     *            the per-task override ({@link Support#AUTO} to defer)
     * @return {@code true} if the capability is supported
     */
    public boolean supports(Capability capability, String provider, String model, Support taskOverride) {
        if (taskOverride == Support.ON) {
            return true;
        }
        if (taskOverride == Support.OFF) {
            return false;
        }
        Support deployment = deploymentOverride(capability, provider);
        if (deployment == Support.ON) {
            return true;
        }
        if (deployment == Support.OFF) {
            return false;
        }
        return builtInDefault(capability, normalize(provider), normalize(model));
    }

    private Support deploymentOverride(Capability capability, String provider) {
        String p = normalize(provider);
        if (!p.isEmpty()) {
            Support providerSpecific = Support.parse(
                    configLookup.apply(CONFIG_PREFIX + p + "." + capability.configSuffix()).orElse(null));
            if (providerSpecific != Support.AUTO) {
                return providerSpecific;
            }
        }
        return Support.parse(configLookup.apply(CONFIG_PREFIX + capability.configSuffix()).orElse(null));
    }

    private boolean builtInDefault(Capability capability, String provider, String model) {
        if (provider.isEmpty()) {
            return false;
        }
        return switch (capability) {
            case VISION -> defaultVision(provider, model);
            case DOCUMENTS -> defaultDocuments(provider, model);
            case AUDIO -> defaultAudio(provider);
            case IMAGE_URL -> defaultImageUrl(provider);
        };
    }

    // ----- Vision -------------------------------------------------------------

    private boolean defaultVision(String provider, String model) {
        return switch (provider) {
            // Vision-first providers: on by default, downgraded for known text-only models.
            case LLM_TYPE_OPENAI, LLM_TYPE_AZURE_OPENAI, LLM_TYPE_ANTHROPIC,
                    LLM_TYPE_GEMINI, LLM_TYPE_GEMINI_VERTEX, LLM_TYPE_MISTRAL ->
                !isKnownTextOnlyModel(model);
            // Model-dependent providers: off by default, upgraded for known vision models.
            case LLM_TYPE_OLLAMA, LLM_TYPE_BEDROCK, LLM_TYPE_ORACLE_GENAI -> isKnownVisionModel(model);
            // No vision support.
            default -> false;
        };
    }

    private static boolean isKnownTextOnlyModel(String model) {
        if (model.isEmpty()) {
            return false;
        }
        return model.contains("gpt-3.5")
                || model.contains("text-davinci")
                || model.contains("davinci")
                || model.contains("babbage")
                || model.contains("text-embedding")
                || model.contains("-embed")
                || model.contains("embed-")
                || model.contains("text-moderation")
                || model.contains("mistral-embed")
                || model.contains("mistral-7b")
                || model.contains("mixtral");
    }

    private static boolean isKnownVisionModel(String model) {
        if (model.isEmpty()) {
            return false;
        }
        return model.contains("llava")
                || model.contains("bakllava")
                || model.contains("vision")
                || model.contains("pixtral")
                || model.contains("claude-3")
                || model.contains("claude-sonnet") || model.contains("claude-opus") || model.contains("claude-haiku")
                || model.contains("nova-lite") || model.contains("nova-pro") || model.contains("nova-premier")
                || model.contains("llama3.2") || model.contains("llama-3.2") || model.contains("llama3-2")
                || model.contains("gemma3") || model.contains("gemma-3")
                || model.contains("qwen2-vl") || model.contains("qwen2.5-vl")
                || model.contains("minicpm-v") || model.contains("moondream");
    }

    // ----- Documents (native PDF) --------------------------------------------

    private boolean defaultDocuments(String provider, String model) {
        return switch (provider) {
            case LLM_TYPE_ANTHROPIC -> !isLegacyAnthropicModel(model);
            case LLM_TYPE_GEMINI, LLM_TYPE_GEMINI_VERTEX -> true;
            // OpenAI/Azure native PDF is model-dependent and inconsistent → conservative
            // off
            // (falls back to text extraction). Everything else: no native documents.
            default -> false;
        };
    }

    private static boolean isLegacyAnthropicModel(String model) {
        // Native PDF/document support arrived with Claude 3; Claude 2 / instant lack
        // it.
        return model.contains("claude-2") || model.contains("claude-instant") || model.contains("claude-1");
    }

    // ----- Audio --------------------------------------------------------------

    private boolean defaultAudio(String provider) {
        return switch (provider) {
            case LLM_TYPE_GEMINI, LLM_TYPE_GEMINI_VERTEX -> true;
            default -> false;
        };
    }

    // ----- Image by URL -------------------------------------------------------

    private boolean defaultImageUrl(String provider) {
        // Only OpenAI/Azure reliably fetch images by URL. Every other provider needs
        // the bytes inlined, so the forwarder downloads and base64-encodes instead.
        return switch (provider) {
            case LLM_TYPE_OPENAI, LLM_TYPE_AZURE_OPENAI -> true;
            default -> false;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
