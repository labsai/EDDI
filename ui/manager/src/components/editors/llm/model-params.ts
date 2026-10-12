/**
 * Which parameters each provider's backend builder actually reads for the model
 * and the credential, checked against
 * `src/main/java/ai/labs/eddi/modules/llm/impl/builder/*LanguageModelBuilder`
 * (`recognisedParameters()` and the `KEY_*` constants).
 *
 * The default is what Anthropic, OpenAI, the OpenAI-compatible family (which
 * delegates to the OpenAI builder), Gemini, Mistral and Oracle GenAI read:
 * `modelName`, and `apiKey` where the provider takes one. A key a builder does
 * not recognise is ignored, so writing the wrong one produces a task that looks
 * configured and has no model or credential.
 */
export interface ModelParamSpec {
  /** The parameter that names the model. */
  modelKey: string;
  /**
   * The parameter that carries the credential, or null when the provider has
   * none: Ollama, Bedrock (AWS credential chain), Vertex (GCP application
   * default credentials) and Oracle GenAI (OCI config) authenticate elsewhere.
   */
  credentialKey: string | null;
  /** The credential is optional rather than required (Jlama's Hugging Face token). */
  credentialOptional?: boolean;
}

const DEFAULT_SPEC: ModelParamSpec = { modelKey: "modelName", credentialKey: "apiKey" };

const SPECS: Record<string, ModelParamSpec> = {
  // OllamaLanguageModelBuilder: `model`, no credential.
  ollama: { modelKey: "model", credentialKey: null },
  // BedrockLanguageModelBuilder: `modelId`; credentials come from the AWS chain.
  bedrock: { modelKey: "modelId", credentialKey: null },
  // HuggingFaceLanguageModelBuilder: `modelId` and `accessToken`.
  huggingface: { modelKey: "modelId", credentialKey: "accessToken" },
  // VertexGeminiLanguageModelBuilder: `modelId` (legacy `modelID`), ADC for auth.
  "gemini-vertex": { modelKey: "modelId", credentialKey: null },
  // AzureOpenAiLanguageModelBuilder: a deployment, not a model id.
  "azure-openai": { modelKey: "deploymentName", credentialKey: "apiKey" },
  // OracleGenAiLanguageModelBuilder: modelName, OCI config for auth.
  "oracle-genai": { modelKey: "modelName", credentialKey: null },
  // JlamaLanguageModelBuilder: modelName, optional `authToken` for gated repos.
  jlama: { modelKey: "modelName", credentialKey: "authToken", credentialOptional: true },
};

export function modelParamSpec(type: string | undefined): ModelParamSpec {
  return SPECS[type ?? ""] ?? DEFAULT_SPEC;
}

/**
 * Parameters edited by the dedicated "Model" section for this provider. They are
 * left out of the generic grid so a value has one place to live; every other key
 * (including an `apiKey` on a provider that ignores it) stays visible there.
 * Every builder reads `temperature`.
 */
export function dedicatedParamKeys(type: string | undefined): Set<string> {
  const spec = modelParamSpec(type);
  const keys = new Set([spec.modelKey, "temperature"]);
  if (spec.credentialKey) keys.add(spec.credentialKey);
  return keys;
}
