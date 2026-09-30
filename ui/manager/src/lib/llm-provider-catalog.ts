/**
 * Named OpenAI-compatible providers (xAI, DeepSeek, Kimi, Qwen, GLM, MiniMax,
 * OpenRouter, Groq).
 *
 * A static mirror of the backend catalog,
 * `src/main/resources/llm/openai-compatible-providers.json`, which is the single
 * source of truth. `llm-provider-catalog.test.ts` reads that file and fails on
 * any drift, so edit both together.
 */

export type ProviderGroup = "frontier" | "compatible" | "cloud" | "local";

export interface ProviderRegion {
  /**
   * Region id: `intl`, `cn` or `us`. The wizard and the operator form send the
   * region's `baseUrl` (the default region sends none); the backend also accepts
   * the id as a `region` parameter in hand-written configs.
   */
  id: string;
  baseUrl: string;
}

export interface CompatibleProvider {
  id: string;
  name: string;
  defaultBaseUrl: string;
  regions: readonly ProviderRegion[];
  defaultModel: string;
  suggestions: readonly string[];
  apiKeyUrl: string;
  keyPlaceholder: string;
}

export const COMPATIBLE_PROVIDERS: readonly CompatibleProvider[] = [
  {
    id: "xai",
    name: "xAI Grok",
    defaultBaseUrl: "https://api.x.ai/v1",
    regions: [
      { id: "intl", baseUrl: "https://api.x.ai/v1" },
      { id: "us", baseUrl: "https://us.api.x.ai/v1" },
    ],
    defaultModel: "grok-4.7",
    suggestions: ["grok-4.7", "grok-4.6", "grok-4.5", "grok-4.3", "grok-4.20-0309-reasoning", "grok-4.20-0309-non-reasoning"],
    apiKeyUrl: "https://console.x.ai",
    keyPlaceholder: "xai-...",
  },
  {
    id: "deepseek",
    name: "DeepSeek",
    defaultBaseUrl: "https://api.deepseek.com",
    regions: [],
    defaultModel: "deepseek-flash",
    suggestions: ["deepseek-flash", "deepseek-v4-pro"],
    apiKeyUrl: "https://platform.deepseek.com",
    keyPlaceholder: "sk-...",
  },
  {
    id: "moonshot",
    name: "Moonshot Kimi",
    defaultBaseUrl: "https://api.moonshot.ai/v1",
    regions: [
      { id: "intl", baseUrl: "https://api.moonshot.ai/v1" },
      { id: "cn", baseUrl: "https://api.moonshot.cn/v1" },
    ],
    defaultModel: "kimi-k3",
    suggestions: ["kimi-k3", "kimi-k2.6", "kimi-k2.7-code", "kimi-k2.7-code-highspeed"],
    apiKeyUrl: "https://platform.kimi.ai/console/api-keys",
    keyPlaceholder: "sk-...",
  },
  {
    id: "qwen",
    name: "Alibaba Qwen",
    defaultBaseUrl: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
    regions: [
      { id: "intl", baseUrl: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1" },
      { id: "cn", baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1" },
      { id: "us", baseUrl: "https://dashscope-us.aliyuncs.com/compatible-mode/v1" },
    ],
    defaultModel: "qwen3.7-plus",
    suggestions: ["qwen3.7-plus", "qwen3.8-max", "qwen3.8-flash", "qwen3.7-flash"],
    apiKeyUrl: "https://modelstudio.console.alibabacloud.com",
    keyPlaceholder: "sk-...",
  },
  {
    id: "zhipu",
    name: "Z.ai GLM (Zhipu)",
    defaultBaseUrl: "https://api.z.ai/api/paas/v4",
    regions: [
      { id: "intl", baseUrl: "https://api.z.ai/api/paas/v4" },
      { id: "cn", baseUrl: "https://open.bigmodel.cn/api/paas/v4" },
    ],
    defaultModel: "glm-5.3",
    suggestions: ["glm-5.3", "glm-5.3-flash", "glm-5.3-flashx", "glm-5.2"],
    apiKeyUrl: "https://z.ai/manage-apikey/apikey-list",
    keyPlaceholder: "...",
  },
  {
    id: "minimax",
    name: "MiniMax",
    defaultBaseUrl: "https://api.minimax.io/v1",
    regions: [
      { id: "intl", baseUrl: "https://api.minimax.io/v1" },
      { id: "cn", baseUrl: "https://api.minimax.cn/v1" },
    ],
    defaultModel: "MiniMax-M3",
    suggestions: ["MiniMax-M3", "MiniMax-M3.1-Flash-Preview", "MiniMax-M2.7", "MiniMax-M2.7-highspeed"],
    apiKeyUrl: "https://platform.minimax.io",
    keyPlaceholder: "...",
  },
  {
    id: "openrouter",
    name: "OpenRouter",
    defaultBaseUrl: "https://openrouter.ai/api/v1",
    regions: [],
    defaultModel: "openrouter/auto",
    suggestions: ["openrouter/auto"],
    apiKeyUrl: "https://openrouter.ai/keys",
    keyPlaceholder: "sk-or-...",
  },
  {
    id: "groq",
    name: "Groq",
    defaultBaseUrl: "https://api.groq.com/openai/v1",
    regions: [],
    defaultModel: "openai/gpt-oss-120b",
    suggestions: ["openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b"],
    apiKeyUrl: "https://console.groq.com/keys",
    keyPlaceholder: "gsk_...",
  },
];

const DEFAULT_KEY_PLACEHOLDER = "sk-...";

export function getCompatibleProvider(id: string): CompatibleProvider | undefined {
  return COMPATIBLE_PROVIDERS.find((p) => p.id === id);
}

/** Region choices for a provider; empty when it has a single endpoint. */
export function getProviderRegions(id: string): readonly ProviderRegion[] {
  return getCompatibleProvider(id)?.regions ?? [];
}

/** The endpoint a compatible provider uses when no base URL is configured. */
export function getDefaultBaseUrl(id: string): string | undefined {
  return getCompatibleProvider(id)?.defaultBaseUrl;
}

export function getKeyPlaceholder(id: string): string {
  return getCompatibleProvider(id)?.keyPlaceholder ?? DEFAULT_KEY_PLACEHOLDER;
}

export function getApiKeyUrl(id: string): string | undefined {
  return getCompatibleProvider(id)?.apiKeyUrl;
}
