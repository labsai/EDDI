import { describe, it, expect } from "vitest";
import { isCredentialKey, maskCredentials } from "@/lib/mask-credentials";

describe("maskCredentials", () => {
  it("masks literal values under credential-shaped keys, at any depth", () => {
    const masked = maskCredentials({
      tasks: [{ parameters: { apiKey: "sk-live-123456", model: "gpt", botToken: "xoxb-1" } }], // gitleaks:allow
      password: "hunter2", // gitleaks:allow
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
    const a = maskCredentials({ apiKey: "one-secret-value" }) as { apiKey: string }; // gitleaks:allow
    const b = maskCredentials({ apiKey: "two-secret-value" }) as { apiKey: string }; // gitleaks:allow
    const c = maskCredentials({ apiKey: "one-secret-value" }) as { apiKey: string }; // gitleaks:allow
    expect(a.apiKey).not.toBe(b.apiKey);
    expect(a.apiKey).toBe(c.apiKey);
  });

  it("does not treat token counts as credentials", () => {
    expect(isCredentialKey("maxTokens")).toBe(false);
    expect(isCredentialKey("accessToken")).toBe(true);
    expect(maskCredentials({ maxTokens: 1000 })).toEqual({ maxTokens: 1000 });
  });

  it("leaves references readable, also beside a scheme word or as a template", () => {
    const masked = maskCredentials({
      headers: { Authorization: "Bearer ${vault:openai}", "X-Api-Key": "{properties.apiKey}" },
      apiKey: "${caller:token}",
      password: "${connection:db}",
    }) as { headers: Record<string, string>; apiKey: string; password: string };
    expect(masked.headers.Authorization).toBe("Bearer ${vault:openai}");
    expect(masked.headers["X-Api-Key"]).toBe("{properties.apiKey}");
    expect(masked.apiKey).toBe("${caller:token}");
    expect(masked.password).toBe("${connection:db}");
  });

  it("masks literals the backend redacts: header names ending in key, cookies, pwd, connection strings", () => {
    const masked = maskCredentials({
      headers: { "Ocp-Apim-Subscription-Key": "abc123456", Cookie: "sid=1", Accept: "text/plain" }, // gitleaks:allow
      pwd: "hunter2", // gitleaks:allow
      connectionString: "Server=x;Password=y",
    }) as { headers: Record<string, string>; pwd: string; connectionString: string };
    expect(masked.headers["Ocp-Apim-Subscription-Key"]).not.toContain("abc123");
    expect(masked.headers.Cookie).not.toContain("sid=1");
    expect(masked.headers.Accept).toBe("text/plain");
    expect(masked.pwd).not.toContain("hunter2");
    expect(masked.connectionString).not.toContain("Password");
  });

  it("does not mask names the backend does not redact", () => {
    expect(maskCredentials({ credentialId: "abc-123", passwordPolicy: "strong" })).toEqual({
      credentialId: "abc-123",
      passwordPolicy: "strong",
    });
  });

  it("masks credentials inside URLs: userinfo and credential-named query parameters", () => {
    const masked = maskCredentials({
      url: "https://bot:s3cret@example.com/v1?api_key=abcdef&page=2",
      other: "plain text",
    }) as { url: string; other: string };
    expect(masked.url).not.toContain("s3cret");
    expect(masked.url).not.toContain("abcdef");
    expect(masked.url).toContain("page=2");
    expect(masked.url).toContain("bot:");
    expect(masked.other).toBe("plain text");
  });
});
