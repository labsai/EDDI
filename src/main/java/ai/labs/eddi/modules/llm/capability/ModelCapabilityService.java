/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.capability;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.*;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 * Unknown providers <em>and unknown models</em> resolve to
 * <em>unsupported</em>, so the forwarder falls back to text extraction or a
 * metadata note and never sends content that would error the provider.
 * Capability is model-level; the defaults are deliberately cautious and should
 * be verified against the langchain4j release in use.
 * <p>
 * <b>Failing closed is the whole point of the built-in table.</b> The
 * vision-first providers used to be resolved as "supported unless the model is
 * a known text-only one", which inverted this contract: any model name absent
 * from the table — a new release, a fine-tune, a provider-side alias — was
 * treated as multimodal, the attachment was forwarded, and the provider
 * answered with a 400. Every default below is therefore an <em>allow-list</em>:
 * a model must be recognised to be considered capable. A deployment running a
 * model the table does not know yet asserts the capability explicitly, either
 * per task ({@code "multimodal": {"vision": "on"}} on the LLM task) or per
 * deployment ({@code eddi.multimodal.<provider>.vision=on}) — both take
 * precedence over this table.
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

    private static final Logger LOGGER = Logger.getLogger(ModelCapabilityService.class);

    /** Azure deployment names already reported as unrecognised — one WARN each. */
    private final Set<String> warnedAzureDeployments = ConcurrentHashMap.newKeySet();

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
            case AUDIO -> defaultAudio(provider, model);
            case IMAGE_URL -> defaultImageUrl(provider, model);
        };
    }

    // ----- Vision -------------------------------------------------------------

    /**
     * Vision is granted only to a model this table recognises as multimodal — for
     * every provider, including the "vision-first" cloud ones. An unrecognised
     * model name (including a blank one) is unsupported.
     * <p>
     * Azure is the awkward case: its "model name" is the operator-chosen
     * {@code deploymentName}, not a family name, so an allow-list keyed on the
     * model name can deny a perfectly capable deployment. Separator-insensitive
     * matching catches the common shapes ({@code gpt4o-prod}, {@code gpt_4o_eu});
     * anything else is denied — but LOUDLY, naming the override, rather than
     * silently dropping attachments.
     */
    private boolean defaultVision(String provider, String model) {
        return switch (provider) {
            case LLM_TYPE_AZURE_OPENAI -> {
                boolean supported = isKnownVisionModel(model) && !isKnownTextOnlyModel(model);
                if (!supported) {
                    warnOnceAboutUnrecognisedAzureDeployment(model);
                }
                yield supported;
            }
            case LLM_TYPE_OPENAI, LLM_TYPE_ANTHROPIC,
                    LLM_TYPE_GEMINI, LLM_TYPE_GEMINI_VERTEX, LLM_TYPE_MISTRAL,
                    LLM_TYPE_OLLAMA, LLM_TYPE_BEDROCK, LLM_TYPE_ORACLE_GENAI ->
                isKnownVisionModel(model) && !isKnownTextOnlyModel(model);
            // No vision support.
            default -> false;
        };
    }

    /**
     * One WARN per distinct deployment name. Capability is resolved per attachment
     * per turn, so an unconditional warning would repeat forever; without any
     * warning the operator's only symptom is that images stopped reaching the
     * model.
     */
    private void warnOnceAboutUnrecognisedAzureDeployment(String model) {
        if (warnedAzureDeployments.add(model)) {
            LOGGER.warnf("Azure OpenAI deployment '%s' is not recognised as a vision model, so images will NOT be "
                    + "forwarded to it. Azure deployment names are operator-chosen, so this table cannot know: if the "
                    + "deployment does serve a multimodal model, set eddi.multimodal.%s.vision=on (or "
                    + "\"multimodal\": {\"vision\": \"on\"} on the LLM task).", sanitize(model), LLM_TYPE_AZURE_OPENAI);
        }
    }

    /**
     * Models that are explicitly text-only. Kept as a veto on top of
     * {@link #isKnownVisionModel} because a family match can be too broad —
     * {@code gemini-embedding-*} matches the Gemini family but is an embedding
     * model.
     */
    private static boolean isKnownTextOnlyModel(String model) {
        if (model.isEmpty()) {
            return false;
        }
        if (model.contains("gpt-3.5")
                || model.contains("text-davinci")
                || model.contains("davinci")
                || model.contains("babbage")
                || model.contains("text-embedding")
                || model.contains("-embed")
                || model.contains("embed-")
                || model.contains("text-moderation")
                || model.contains("mistral-embed")
                || model.contains("mistral-7b")
                || model.contains("mixtral")) {
            return true;
        }
        // Separator-insensitive veto, for the same reason the vision list has one:
        // an Azure deployment called "gpt35turbo" must still be text-only.
        String compact = compact(model);
        return compact.contains("gpt35")
                || compact.contains("textembedding")
                || compact.contains("textmoderation")
                || compact.contains("mistralembed")
                || compact.contains("mistral7b");
    }

    /**
     * The model string with every separator removed, so {@code gpt-4o},
     * {@code gpt4o} and {@code gpt_4o_prod} all reduce to a form containing
     * {@code gpt4o}.
     * <p>
     * Azure's "model name" is the operator-chosen {@code deploymentName} (see
     * {@code LlmTask.resolveModelName}), which almost never reproduces the
     * canonical punctuation. Matching only the punctuated family token denied
     * vision to deployments like {@code gpt4o-prod} — capable models whose only sin
     * was a naming convention.
     */
    private static String compact(String model) {
        return model.replaceAll("[^a-z0-9]", "");
    }

    /**
     * The allow-list of multimodal models, spanning every provider — hosted
     * families and locally served open-weight models alike. Provider prefixes vary
     * ({@code anthropic.claude-3-sonnet} on Bedrock,
     * {@code meta.llama-3.2-90b-vision} on Oracle), so matching is by substring on
     * the family token rather than by exact name.
     */
    private static boolean isKnownVisionModel(String model) {
        if (model.isEmpty()) {
            return false;
        }
        return isKnownOpenAiVisionModel(model)
                || isModernAnthropicModel(model)
                || isKnownGeminiModel(model)
                || model.contains("llava")
                || model.contains("bakllava")
                || model.contains("vision")
                || model.contains("pixtral")
                || model.contains("nova-lite") || model.contains("nova-pro") || model.contains("nova-premier")
                || model.contains("llama3.2") || model.contains("llama-3.2") || model.contains("llama3-2")
                || model.contains("gemma3") || model.contains("gemma-3")
                || model.contains("qwen2-vl") || model.contains("qwen2.5-vl")
                || model.contains("minicpm-v") || model.contains("moondream");
    }

    /**
     * OpenAI/Azure multimodal families. {@code gpt-4} alone is deliberately NOT a
     * token: the original {@code gpt-4} and {@code gpt-4-32k} are text-only, and a
     * bare prefix match would sweep them in.
     */
    private static boolean isKnownOpenAiVisionModel(String model) {
        if (model.contains("gpt-4o")
                || model.contains("gpt-4-turbo")
                || model.contains("gpt-4.1") || model.contains("gpt-4-1")
                || model.contains("gpt-4.5")
                || model.contains("gpt-5")
                || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")) {
            return true;
        }
        // Separator-insensitive, for Azure deployment names (see compact()).
        // "gpt4" alone stays out: the original gpt-4 / gpt-4-32k are text-only.
        String compact = compact(model);
        return compact.contains("gpt4o")
                || compact.contains("gpt4turbo")
                || compact.contains("gpt41")
                || compact.contains("gpt45")
                || compact.contains("gpt5");
    }

    /** Any Claude model, including the legacy pre-Claude-3 ones. */
    private static boolean isKnownAnthropicModel(String model) {
        return model.contains("claude");
    }

    /**
     * A Claude model from the multimodal generation. Claude 2 / instant / 1 accept
     * neither images nor native PDFs.
     */
    private static boolean isModernAnthropicModel(String model) {
        return isKnownAnthropicModel(model) && !isLegacyAnthropicModel(model);
    }

    /**
     * Any Gemini model; the embedding variants are vetoed by the text-only list.
     */
    private static boolean isKnownGeminiModel(String model) {
        return model.contains("gemini");
    }

    // ----- Documents (native PDF) --------------------------------------------

    private boolean defaultDocuments(String provider, String model) {
        return switch (provider) {
            // Native PDF/document support arrived with Claude 3; an unrecognised model is
            // not assumed to be a modern Claude.
            case LLM_TYPE_ANTHROPIC -> isModernAnthropicModel(model);
            case LLM_TYPE_GEMINI, LLM_TYPE_GEMINI_VERTEX -> isKnownGeminiModel(model) && !isKnownTextOnlyModel(model);
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

    private boolean defaultAudio(String provider, String model) {
        return switch (provider) {
            case LLM_TYPE_GEMINI, LLM_TYPE_GEMINI_VERTEX -> isKnownGeminiModel(model) && !isKnownTextOnlyModel(model);
            default -> false;
        };
    }

    // ----- Image by URL -------------------------------------------------------

    private boolean defaultImageUrl(String provider, String model) {
        // Only OpenAI/Azure reliably fetch images by URL, and only for a model that can
        // see at all. Every other provider needs the bytes inlined, so the forwarder
        // downloads and base64-encodes instead.
        return switch (provider) {
            case LLM_TYPE_OPENAI, LLM_TYPE_AZURE_OPENAI -> isKnownVisionModel(model) && !isKnownTextOnlyModel(model);
            default -> false;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
