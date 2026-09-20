import { describe, expect, it } from "vitest";
import {
  MODEL_SUGGESTIONS,
  isBaseUrlRequired,
  supportsBaseUrl,
} from "@/lib/model-suggestions";
import { LLM_PROVIDERS, getProviderConfig } from "@/lib/api/agent-setup";

/**
 * The wizard's model catalog is the one place a user's provider choice turns
 * into a value the backend will actually try to load. A suggestion that cannot
 * load is worse than no suggestion: the wizard reports success and the agent
 * fails on its first message, by which time nobody is looking at this screen.
 */
describe("model suggestions", () => {
  describe("Jlama", () => {
    /**
     * Jlama resolves a model through `JlamaModelRegistry`, which downloads it
     * from Hugging Face. A bare name (`tinyllama`, `llama-3.2-1b`) has no owner
     * to look up, so the download fails at the first turn.
     */
    it("only suggests Hugging Face repository ids in owner/name form", () => {
      const suggestions = MODEL_SUGGESTIONS.jlama ?? [];
      expect(suggestions.length).toBeGreaterThan(0);

      for (const model of suggestions) {
        expect(
          model.split("/"),
          `"${model}" is not an owner/name repository id — Jlama cannot resolve it`,
        ).toHaveLength(2);
      }
    });

    it("defaults to a model the backend can actually load", () => {
      const jlama = getProviderConfig("jlama");
      expect(jlama).toBeDefined();
      expect(jlama!.defaultModel.split("/")).toHaveLength(2);
      // The placeholder the user sees before typing must itself be usable.
      expect(MODEL_SUGGESTIONS.jlama).toContain(jlama!.defaultModel);
    });

    /**
     * Jlama runs in the EDDI JVM. `JlamaLanguageModelBuilder.recognisedParameters()`
     * has no `baseUrl`, and `AgentSetupService` drops it before the builder is
     * reached — so a URL entered here is silently discarded. It used to be
     * *required*, which blocked operator activation on a field with no effect.
     */
    it("offers no base URL field — there is no endpoint", () => {
      expect(supportsBaseUrl("jlama")).toBe(false);
      expect(isBaseUrlRequired("jlama")).toBe(false);
    });
  });

  it("requires a base URL only for providers that talk to a model server", () => {
    expect(isBaseUrlRequired("ollama")).toBe(true);
    expect(isBaseUrlRequired("anthropic")).toBe(false);
  });

  it("offers an optional base URL for every provider with an endpoint", () => {
    for (const provider of LLM_PROVIDERS) {
      if (provider.id === "jlama") continue;
      expect(supportsBaseUrl(provider.id)).toBe(true);
    }
  });

  /**
   * A provider whose default model is not in its own suggestion list is not
   * wrong on its own — the default is a placeholder, and a catalog can be
   * trimmed. But a default that is *shaped* unlike every suggestion for that
   * provider is how `llama-3.2-1b` survived: it looked like a model name
   * because nobody compared it to what the provider actually accepts.
   */
  it("gives every provider at least one suggestion or a default", () => {
    for (const provider of LLM_PROVIDERS) {
      const suggestions = MODEL_SUGGESTIONS[provider.id] ?? [];
      expect(
        suggestions.length > 0 || provider.defaultModel.length > 0,
        `provider "${provider.id}" offers the user nothing to start from`,
      ).toBe(true);
    }
  });
});
