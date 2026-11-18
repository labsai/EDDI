import { describe, it, expect } from "vitest";
import { isCredentialKey, maskCredentials } from "@/lib/mask-credentials";

describe("maskCredentials", () => {
  it("masks literal values under credential-shaped keys, at any depth", () => {
    const masked = maskCredentials({
      tasks: [{ parameters: { apiKey: "sk-live-123456", model: "gpt", botToken: "xoxb-1" } }],
      password: "hunter2",
    }) as { tasks: { parameters: Record<string, string> }[]; password: string };
    expect(masked.tasks[0]!.parameters.apiKey).not.toContain("sk-live");
    expect(masked.tasks[0]!.parameters.botToken).not.toContain("xoxb");
    expect(masked.tasks[0]!.parameters.model).toBe("gpt");
    expect(masked.password).not.toContain("hunter2");
  });

  it("keeps references and empty values readable", () => {
    const masked = maskCredentials({ apiKey: "${vault:openai-key}", secret: "", token: "${vars:t}" }) as Record<
      string,
      string
    >;
    expect(masked.apiKey).toBe("${vault:openai-key}");
    expect(masked.secret).toBe("");
    expect(masked.token).toBe("${vars:t}");
  });

  it("lets a changed credential still show as a change", () => {
    const a = maskCredentials({ apiKey: "one-secret-value" }) as { apiKey: string };
    const b = maskCredentials({ apiKey: "two-secret-value" }) as { apiKey: string };
    const c = maskCredentials({ apiKey: "one-secret-value" }) as { apiKey: string };
    expect(a.apiKey).not.toBe(b.apiKey);
    expect(a.apiKey).toBe(c.apiKey);
  });

  it("does not treat token counts as credentials", () => {
    expect(isCredentialKey("maxTokens")).toBe(false);
    expect(isCredentialKey("accessToken")).toBe(true);
    expect(maskCredentials({ maxTokens: 1000 })).toEqual({ maxTokens: 1000 });
  });
});
