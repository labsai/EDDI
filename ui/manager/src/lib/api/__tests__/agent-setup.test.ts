import { describe, it, expect } from "vitest";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import {
  setupAgent,
  createApiAgent,
  getProviderConfig,
  LLM_PROVIDERS,
  LLM_PROVIDER_GROUPS,
} from "../agent-setup";
import { MODEL_TYPES } from "@/components/editors/llm/types";

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

    it("every provider has a valid group and ids are unique", () => {
      const groups = new Set(LLM_PROVIDER_GROUPS.map((g) => g.id));
      const ids = new Set<string>();
      for (const provider of LLM_PROVIDERS) {
        expect(groups.has(provider.group)).toBe(true);
        expect(ids.has(provider.id)).toBe(false);
        ids.add(provider.id);
      }
    });

    it("every group has at least one provider", () => {
      for (const group of LLM_PROVIDER_GROUPS) {
        expect(LLM_PROVIDERS.some((p) => p.group === group.id)).toBe(true);
      }
    });

    it("the LLM editor's MODEL_TYPES are exactly the provider ids", () => {
      expect([...MODEL_TYPES]).toEqual(LLM_PROVIDERS.map((p) => p.id));
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
        agentName: "API Agent",
        systemPrompt: "You are an API agent.",
        openApiSpec: "https://example.com/openapi.json",
        provider: "openai",
        model: "gpt-5.4",
      });
      expect(result).toBeDefined();
      expect(result.agentId).toBeDefined();
    });

    // Regression guard: the manager used to send `name`, which the backend drops,
    // making setup-api fail with "Agent name is required".
    it("sends the name as `agentName`, the field the backend requires", async () => {
      const result = await createApiAgent({
        agentName: "Named Correctly",
        systemPrompt: "You are an API agent.",
        openApiSpec: "https://example.com/openapi.json",
      });
      expect(result.agentName).toBe("Named Correctly");
    });
  });
});
