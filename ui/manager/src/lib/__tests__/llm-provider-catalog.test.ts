import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  COMPATIBLE_PROVIDERS,
  getApiKeyUrl,
  getDefaultBaseUrl,
  getKeyPlaceholder,
  getProviderRegions,
} from "@/lib/llm-provider-catalog";
import { LLM_PROVIDERS } from "@/lib/api/agent-setup";
import { MODEL_SUGGESTIONS } from "@/lib/model-suggestions";

/**
 * The backend catalog is the single source of truth for the named
 * OpenAI-compatible providers; the Manager keeps a static mirror. This test
 * fails on any drift between the two.
 */

interface BackendProvider {
  id: string;
  displayName: string;
  defaultBaseUrl: string;
  regions: { id: string; baseUrl: string }[];
  defaultModel: string;
  modelSuggestions: string[];
  apiKeyUrl: string;
  apiKeyPlaceholder: string;
}

// Resolved from this file, not the working directory the runner starts in.
const CATALOG_JSON = resolve(
  __dirname,
  "../../../../../src/main/resources/llm/openai-compatible-providers.json",
);

const backend = (
  JSON.parse(readFileSync(CATALOG_JSON, "utf-8")) as { providers: BackendProvider[] }
).providers;

describe("llm-provider-catalog parity with the backend catalog", () => {
  it("lists exactly the backend's provider ids, in both directions", () => {
    const backendIds = backend.map((p) => p.id).sort();
    expect(COMPATIBLE_PROVIDERS.map((p) => p.id).sort()).toEqual(backendIds);
    expect(
      LLM_PROVIDERS.filter((p) => p.group === "compatible")
        .map((p) => p.id)
        .sort(),
    ).toEqual(backendIds);
  });

  it.each(backend.map((p) => [p.id, p] as const))("%s mirrors its backend entry", (_id, b) => {
    const m = COMPATIBLE_PROVIDERS.find((p) => p.id === b.id);
    expect(m).toBeDefined();
    expect(m!.name).toBe(b.displayName);
    expect(m!.defaultBaseUrl).toBe(b.defaultBaseUrl);
    expect(m!.regions).toEqual(b.regions);
    expect(m!.defaultModel).toBe(b.defaultModel);
    expect([...m!.suggestions]).toEqual(b.modelSuggestions);
    expect(MODEL_SUGGESTIONS[b.id]).toEqual(b.modelSuggestions);
    expect(m!.apiKeyUrl).toBe(b.apiKeyUrl);
    expect(m!.keyPlaceholder).toBe(b.apiKeyPlaceholder);

    const listed = LLM_PROVIDERS.find((p) => p.id === b.id);
    expect(listed?.name).toBe(b.displayName);
    expect(listed?.defaultModel).toBe(b.defaultModel);
    expect(listed?.needsKey).toBe(true);
  });

  it("the default model is always one of the suggestions", () => {
    for (const p of COMPATIBLE_PROVIDERS) {
      expect(p.suggestions).toContain(p.defaultModel);
    }
  });
});

describe("llm-provider-catalog helpers", () => {
  it("returns regions only for providers with more than the default endpoint", () => {
    expect(getProviderRegions("qwen").map((r) => r.id)).toEqual(["intl", "cn", "us"]);
    expect(getProviderRegions("xai").map((r) => r.id)).toEqual(["intl", "us"]);
    expect(getProviderRegions("deepseek")).toEqual([]);
    expect(getProviderRegions("openai")).toEqual([]);
  });

  it("returns the default endpoint only for compatible providers", () => {
    expect(getDefaultBaseUrl("deepseek")).toBe("https://api.deepseek.com");
    expect(getDefaultBaseUrl("openai")).toBeUndefined();
  });

  it("falls back to sk-... for a key placeholder", () => {
    expect(getKeyPlaceholder("xai")).toBe("xai-...");
    expect(getKeyPlaceholder("openai")).toBe("sk-...");
    expect(getApiKeyUrl("openai")).toBeUndefined();
  });
});
