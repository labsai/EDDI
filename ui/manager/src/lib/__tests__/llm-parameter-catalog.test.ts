import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  PARAMETER_CATALOG,
  PARAM_GROUPS,
  UntranslatableRegexError,
  buildParameterCatalog,
  catalogKeys,
  evaluateParameter,
  javaRegexToJs,
  modelIdFor,
  rejectedForEveryValue,
  type RawParameterCatalog,
} from "@/lib/llm-parameter-catalog";
import { LLM_PARAMETER_CATALOG_DATA } from "@/lib/llm-parameter-catalog.data";
import { LLM_PROVIDERS } from "@/lib/api/agent-setup";

// Resolved from this file, not the working directory the runner starts in.
const ROOT = resolve(__dirname, "../../../../..");
const CATALOG_JSON = resolve(ROOT, "src/main/resources/llm/model-parameters.json");
const VECTORS_JSON = resolve(ROOT, "src/test/resources/llm/parameter-rule-vectors.json");

const backend = JSON.parse(readFileSync(CATALOG_JSON, "utf-8")) as RawParameterCatalog;

interface Vector {
  name: string;
  type: string;
  params: Record<string, string>;
  key: string;
  expected: "ok" | "rejected" | "ignored";
}
const vectors = JSON.parse(readFileSync(VECTORS_JSON, "utf-8")) as Vector[];

describe("llm-parameter-catalog mirrors the backend catalog", () => {
  it("is the backend JSON verbatim (regenerate with `npm run gen:llm-params`)", () => {
    expect(LLM_PARAMETER_CATALOG_DATA).toEqual(backend);
  });

  it("describes only provider types the Manager offers", () => {
    const offered = new Set(LLM_PROVIDERS.map((p) => p.id));
    for (const type of Object.keys(backend.providers)) expect(offered, type).toContain(type);
  });

  it("uses only groups the Manager knows how to render", () => {
    for (const provider of PARAMETER_CATALOG.providers.values()) {
      for (const p of provider.params) expect(PARAM_GROUPS, `${provider.type}.${p.key}`).toContain(p.group);
    }
  });

  it("translates every models regex into JavaScript", () => {
    const failures: string[] = [];
    for (const [type, provider] of Object.entries(backend.providers)) {
      for (const p of provider.params) {
        for (const r of p.rules ?? []) {
          try {
            javaRegexToJs(r.models);
          } catch (e) {
            failures.push(`${type}.${p.key}: ${(e as Error).message}`);
          }
        }
      }
    }
    expect(failures).toEqual([]);
  });
});

describe("shared rule vectors (also run by the Java ModelParameterCatalog tests)", () => {
  it("sets the evaluated key in every vector, so adapt() and evaluateParameter() agree", () => {
    for (const v of vectors) expect(v.params[v.key]?.trim(), v.name).toBeTruthy();
  });

  it("covers every verdict", () => {
    expect(new Set(vectors.map((v) => v.expected))).toEqual(new Set(["ok", "rejected", "ignored"]));
  });

  it.each(vectors.map((v) => [v.name, v] as const))("%s", (_name, v) => {
    expect(evaluateParameter(PARAMETER_CATALOG, v.type, v.key, v.params).state).toBe(v.expected);
  });
});

describe("javaRegexToJs", () => {
  it("keeps anchored patterns with lookahead and classes, case-insensitively", () => {
    const re = javaRegexToJs("^gpt-5\\.[1-4](?!\\d)");
    expect(re.flags).toBe("i");
    expect(re.test("GPT-5.1")).toBe(true);
    expect(re.test("gpt-5.10")).toBe(false);
    expect(javaRegexToJs("^(?<family>claude)-").test("claude-x")).toBe(true);
  });

  it.each([
    ["(?i)^gpt", "inline flag"],
    ["^(?i:gpt)", "scoped inline flag"],
    ["^(?>gpt)", "atomic group"],
    ["^gpt-5++", "possessive"],
    ["\\Agpt", "\\A"],
    ["^gpt\\Z", "\\Z"],
    ["^\\Qgpt.5\\E", "quoting"],
    ["^\\p{Alpha}", "property class"],
    ["^[a-z&&[^b]]", "class intersection"],
    ["^[a[b]]", "nested class"],
    ["^\\x{41}", "code point escape"],
    ["^[a-z", "unterminated class"],
  ])("refuses %s (%s)", (pattern) => {
    expect(() => javaRegexToJs(pattern)).toThrow(UntranslatableRegexError);
  });
});

// A synthetic catalog, so each rule shape is tested in isolation from the data.
const synthetic = buildParameterCatalog({
  schemaVersion: 1,
  providers: {
    base: {
      displayName: "Base",
      params: [
        { key: "a", type: "double", group: "sampling", label: "A", description: "a", source: "https://x/a",
          rules: [{ models: "^m-", effect: "rejected", note: "inherited rule" }] },
        { key: "b", type: "string", group: "output", label: "B", description: "b", source: "https://x/b" },
      ],
    },
    child: {
      displayName: "Child",
      inherits: "base",
      params: [
        { key: "b", type: "string", group: "output", label: "B2", description: "b2", source: "https://x/b2",
          rules: [{ models: "^m-", effect: "ignored", note: "own rule" }] },
        { key: "c", type: "boolean", group: "tools", label: "C", description: "c", source: "https://x/c" },
      ],
    },
    shapes: {
      displayName: "Shapes",
      params: [
        { key: "first", type: "string", group: "advanced", label: "F", description: "f", source: "https://x/f",
          rules: [
            { models: "^m-", effect: "ignored", note: "first" },
            { models: "^m-", effect: "rejected", note: "second" },
          ] },
        { key: "whenSet", type: "string", group: "advanced", label: "W", description: "w", source: "https://x/w",
          rules: [{ models: "^m-", effect: "rejected", note: "while other is set", when: { key: "other" } }] },
        { key: "unlessUnsetTrue", type: "string", group: "advanced", label: "U", description: "u", source: "https://x/u",
          rules: [{ models: "^m-", effect: "rejected", note: "u", unless: { key: "other", values: ["x"], whenUnset: true } }] },
        { key: "whenUnsetTrue", type: "string", group: "advanced", label: "V", description: "v", source: "https://x/v",
          rules: [{ models: "^m-", effect: "rejected", note: "v", when: { key: "other", values: ["x"], whenUnset: true } }] },
        { key: "numeric", type: "double", group: "sampling", label: "N", description: "n", source: "https://x/n",
          rules: [{ models: "^m-", effect: "rejected", note: "n", unless: { key: "numeric", values: ["1"] } }] },
        { key: "level", type: "enum", group: "reasoning", label: "L", description: "l", enum: ["low", "high"], source: "https://x/l",
          rules: [{ models: "^m-", effect: "rejected", note: "l" }] },
      ],
    },
  },
});

describe("evaluateParameter rule semantics", () => {
  const m = { modelName: "m-1" };

  it("inherits the parent's parameters without their rules, own entries replacing them in place", () => {
    expect(evaluateParameter(synthetic, "child", "a", { ...m, a: "1" }).state).toBe("ok");
    expect(evaluateParameter(synthetic, "base", "a", { ...m, a: "1" }).state).toBe("rejected");
    expect(evaluateParameter(synthetic, "child", "b", { ...m, b: "1" })).toMatchObject({ state: "ignored", note: "own rule", source: "https://x/b2" });
    expect(synthetic.providers.get("child")!.params.map((p) => p.key)).toEqual(["a", "b", "c"]);
    expect([...catalogKeys(synthetic, "child")]).toEqual(["a", "b", "c"]);
  });

  it("lets the first matching rule decide", () => {
    expect(evaluateParameter(synthetic, "shapes", "first", m)).toMatchObject({ state: "ignored", note: "first" });
  });

  it("treats `when` without values as 'is set'", () => {
    expect(evaluateParameter(synthetic, "shapes", "whenSet", m).state).toBe("ok");
    expect(evaluateParameter(synthetic, "shapes", "whenSet", { ...m, other: "  " }).state).toBe("ok");
    expect(evaluateParameter(synthetic, "shapes", "whenSet", { ...m, other: "y" }).state).toBe("rejected");
  });

  it("uses whenUnset for an unset condition key, in `when` and in `unless`", () => {
    expect(evaluateParameter(synthetic, "shapes", "unlessUnsetTrue", m).state).toBe("ok");
    expect(evaluateParameter(synthetic, "shapes", "unlessUnsetTrue", { ...m, other: "y" }).state).toBe("rejected");
    expect(evaluateParameter(synthetic, "shapes", "unlessUnsetTrue", { ...m, other: " X " }).state).toBe("ok");
    expect(evaluateParameter(synthetic, "shapes", "whenUnsetTrue", m).state).toBe("rejected");
    expect(evaluateParameter(synthetic, "shapes", "whenUnsetTrue", { ...m, other: "y" }).state).toBe("ok");
  });

  it("compares numbers the way Double.parseDouble does", () => {
    for (const same of ["1", "1.0", "1.", "+1", "1e0", "1d", "1.0F", "0x1p0", "10e-1"]) {
      expect(evaluateParameter(synthetic, "shapes", "numeric", { ...m, numeric: same }).state, same).toBe("ok");
    }
    for (const other of ["0.99", "one", "1x", "NaN", "0x1"]) {
      expect(evaluateParameter(synthetic, "shapes", "numeric", { ...m, numeric: other }).state, other).toBe("rejected");
    }
  });

  it("fails open for an unknown type, key or model", () => {
    expect(evaluateParameter(synthetic, "nope", "a", { ...m, a: "1" }).state).toBe("ok");
    expect(evaluateParameter(synthetic, "base", "zzz", m).state).toBe("ok");
    expect(evaluateParameter(synthetic, "base", "a", { modelName: "other" }).state).toBe("ok");
    expect(evaluateParameter(synthetic, "base", "a", {})).toEqual({ state: "ok", source: "https://x/a", model: null });
  });

  it("reports the model the verdict was reached for", () => {
    expect(evaluateParameter(synthetic, "base", "a", m).model).toBe("m-1");
  });
});

describe("modelIdFor", () => {
  it("reads the model keys in the backend's order", () => {
    expect(modelIdFor("openai", { model: "b", modelName: "a" })).toBe("a");
    expect(modelIdFor("deepseek", { model: "b", modelName: "a" })).toBe("a");
  });

  it("prefers the provider's own model key over one merged in from a cascade task", () => {
    expect(modelIdFor("ollama", { modelName: "gpt-5", model: "llama3.3" })).toBe("llama3.3");
    expect(modelIdFor("bedrock", { modelName: "gpt-4o", modelId: "anthropic.claude-opus-4-7" })).toBe("anthropic.claude-opus-4-7");
    expect(modelIdFor("huggingface", { modelName: "x", modelId: "Qwen/Qwen3.5-7B" })).toBe("Qwen/Qwen3.5-7B");
    expect(modelIdFor("gemini-vertex", { modelName: "x", modelId: "gemini-3.5-flash" })).toBe("gemini-3.5-flash");
    expect(modelIdFor("azure-openai", { modelName: "gpt-5", deploymentName: "gpt4o-prod" })).toBe("gpt4o-prod");
    expect(modelIdFor("azure-openai", { modelName: "gpt-5", deploymentName: "d", baseModel: "gpt-4o" })).toBe("gpt-4o");
    // Own key blank: the fixed order still applies.
    expect(modelIdFor("bedrock", { modelName: "gpt-4o", modelId: " " })).toBe("gpt-4o");
    expect(modelIdFor("ollama", { model: "llama" })).toBe("llama");
    expect(modelIdFor("bedrock", { modelID: "legacy" })).toBe("legacy");
    expect(modelIdFor("azure-openai", { deploymentName: "d", baseModel: "gpt-5" })).toBe("gpt-5");
    expect(modelIdFor("openai", { deploymentName: "d", baseModel: "gpt-5" })).toBe("d");
  });

  it("falls back to an OpenAI-compatible provider's default model, and to null otherwise", () => {
    expect(modelIdFor("deepseek", {})).toBe("deepseek-flash");
    expect(modelIdFor("openai", {})).toBeNull();
  });
});

describe("rejectedForEveryValue", () => {
  it("is true only when no value escapes the rejection", () => {
    const m = { modelName: "m-1" };
    expect(rejectedForEveryValue(synthetic, "shapes", "level", m)).toBe(true);
    // numeric is accepted at exactly 1, so its control must stay editable.
    expect(rejectedForEveryValue(synthetic, "shapes", "numeric", { ...m, numeric: "0.5" })).toBe(false);
    expect(rejectedForEveryValue(synthetic, "shapes", "level", { modelName: "x" })).toBe(false);
  });

  it("matches the real catalog: gpt-5 always rejects temperature, claude-opus-5 accepts 1", () => {
    expect(rejectedForEveryValue(PARAMETER_CATALOG, "openai", "temperature", { modelName: "gpt-5" })).toBe(true);
    expect(rejectedForEveryValue(PARAMETER_CATALOG, "anthropic", "temperature", { modelName: "claude-opus-5" })).toBe(false);
    expect(rejectedForEveryValue(PARAMETER_CATALOG, "openai", "reasoningEffort", { modelName: "gpt-5" })).toBe(false);
  });
});

describe("migrateTo", () => {
  it("reports the key the backend sends a rejected value under, while that key is unset", () => {
    expect(evaluateParameter(PARAMETER_CATALOG, "openai", "maxTokens", { modelName: "gpt-5", maxTokens: "100" })).toMatchObject({
      state: "rejected",
      migratesTo: "maxCompletionTokens",
    });
    // With the target set, the value is simply dropped.
    expect(
      evaluateParameter(PARAMETER_CATALOG, "openai", "maxTokens", { modelName: "gpt-5", maxTokens: "100", maxCompletionTokens: " 50 " }),
    ).not.toHaveProperty("migratesTo");
    expect(evaluateParameter(PARAMETER_CATALOG, "openai", "maxTokens", { modelName: "gpt-4o", maxTokens: "100" })).toEqual({
      state: "ok",
      source: expect.any(String),
      model: "gpt-4o",
    });
  });
});
