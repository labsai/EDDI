import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  setupAgent,
  createApiAgent,
  getProviderConfig,
  LLM_PROVIDERS,
} from "../agent-setup";

describe("agent-setup API", () => {
  // ─── Pure function tests ────────────────────────────────────────
  describe("getProviderConfig", () => {
    it("returns config for known provider", () => {
      const config = getProviderConfig("openai");
      expect(config).toBeDefined();
      expect(config!.name).toBe("OpenAI");
      expect(config!.needsKey).toBe(true);
    });

    it("returns config for ollama", () => {
      const config = getProviderConfig("ollama");
      expect(config).toBeDefined();
      expect(config!.needsKey).toBe(false);
    });

    it("returns undefined for unknown provider", () => {
      const config = getProviderConfig("nonexistent");
      expect(config).toBeUndefined();
    });
  });

  describe("LLM_PROVIDERS", () => {
    it("has at least 10 providers", () => {
      expect(LLM_PROVIDERS.length).toBeGreaterThanOrEqual(10);
    });

    it("every provider has required fields", () => {
      for (const provider of LLM_PROVIDERS) {
        expect(provider.id).toBeDefined();
        expect(provider.name).toBeDefined();
        expect(provider.defaultModel).toBeDefined();
        expect(typeof provider.needsKey).toBe("boolean");
      }
    });
  });

  // ─── API function tests ─────────────────────────────────────────
  describe("setupAgent", () => {
    it("creates an agent via setup wizard", async () => {
      const result = await setupAgent({
        name: "Test Agent",
        systemPrompt: "You are a helpful assistant.",
        provider: "openai",
        model: "gpt-5.4",
      });
      expect(result).toBeDefined();
      expect(result.action).toBe("created");
      expect(result.agentId).toBeDefined();
    });

    it("handles API error", async () => {
      server.use(
        http.post("*/administration/agents/setup", () =>
          HttpResponse.json({ message: "Error" }, { status: 500 })
        )
      );
      await expect(
        setupAgent({
          name: "Fail Agent",
          systemPrompt: "test",
        })
      ).rejects.toMatchObject({ status: 500 });
    });
  });

  describe("createApiAgent", () => {
    it("creates an API agent", async () => {
      const result = await createApiAgent({
        name: "API Agent",
        systemPrompt: "You are an API agent.",
        openApiSpec: "https://example.com/openapi.json",
        provider: "openai",
        model: "gpt-5.4",
      });
      expect(result).toBeDefined();
      expect(result.agentId).toBeDefined();
    });
  });
});
