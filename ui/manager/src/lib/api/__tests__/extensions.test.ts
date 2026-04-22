import { describe, it, expect } from "vitest";
import { RESOURCE_TYPES } from "../resources";
import { EXTENSION_TO_RESOURCE_SLUG, hasResourceStore } from "../extensions";

/**
 * These tests validate that the frontend extension registry and RESOURCE_TYPES
 * config are aligned with the EDDI backend's actual URI schemes and store paths.
 *
 * Backend ground truth (from IRestXxxStore.java):
 *   ai.labs.rules     → rulestore/rulesets
 *   ai.labs.apicalls   → apicallstore/apicalls
 *   ai.labs.output     → outputstore/outputsets
 *   ai.labs.dictionary → dictionarystore/dictionaries
 *   ai.labs.llm        → llmstore/llms
 *   ai.labs.property   → propertysetterstore/propertysetters
 *   ai.labs.mcpcalls   → mcpcallsstore/mcpcalls
 *   ai.labs.rag        → ragstore/rags
 *   ai.labs.snippet    → snippetstore/snippets
 */

describe("RESOURCE_TYPES — extension field alignment with backend", () => {
  const BACKEND_EXTENSIONS: Record<string, string> = {
    rules: "ai.labs.rules",
    apicalls: "ai.labs.apicalls",
    output: "ai.labs.output",
    dictionary: "ai.labs.dictionary",
    llm: "ai.labs.llm",
    propertysetter: "ai.labs.property",
    mcpcalls: "ai.labs.mcpcalls",
    rag: "ai.labs.rag",
    snippets: "ai.labs.snippet",
  };

  it("has 9 resource types", () => {
    expect(RESOURCE_TYPES).toHaveLength(9);
  });

  it("every resource type has an extension field", () => {
    for (const rt of RESOURCE_TYPES) {
      expect(rt.extension, `${rt.slug} missing extension field`).toBeDefined();
      expect(rt.extension.length).toBeGreaterThan(0);
    }
  });

  it.each(Object.entries(BACKEND_EXTENSIONS))(
    "%s has correct extension %s",
    (slug, expectedExtension) => {
      const rt = RESOURCE_TYPES.find((r) => r.slug === slug);
      expect(rt, `Resource type '${slug}' not found`).toBeDefined();
      expect(rt!.extension).toBe(expectedExtension);
    }
  );

  it("propertysetter extension is NOT ai.labs.propertysetter", () => {
    const rt = RESOURCE_TYPES.find((r) => r.slug === "propertysetter");
    expect(rt!.extension).not.toBe("ai.labs.propertysetter");
    expect(rt!.extension).toBe("ai.labs.property");
  });

  it("snippets extension is NOT ai.labs.snippets (singular)", () => {
    const rt = RESOURCE_TYPES.find((r) => r.slug === "snippets");
    expect(rt!.extension).not.toBe("ai.labs.snippets");
    expect(rt!.extension).toBe("ai.labs.snippet");
  });
});

describe("EXTENSION_TO_RESOURCE_SLUG — completeness", () => {
  it("has entries for all 9 resource types", () => {
    const requiredExtensions = [
      "ai.labs.rules",
      "ai.labs.apicalls",
      "ai.labs.output",
      "ai.labs.dictionary",
      "ai.labs.llm",
      "ai.labs.property",
      "ai.labs.mcpcalls",
      "ai.labs.rag",
      "ai.labs.snippet",
    ];

    for (const ext of requiredExtensions) {
      expect(
        EXTENSION_TO_RESOURCE_SLUG[ext],
        `Missing mapping for ${ext}`
      ).toBeDefined();
    }
  });

  it("maps ai.labs.property → propertysetter", () => {
    expect(EXTENSION_TO_RESOURCE_SLUG["ai.labs.property"]).toBe("propertysetter");
  });

  it("maps ai.labs.snippet → snippets", () => {
    expect(EXTENSION_TO_RESOURCE_SLUG["ai.labs.snippet"]).toBe("snippets");
  });

  it("maps ai.labs.rag → rag", () => {
    expect(EXTENSION_TO_RESOURCE_SLUG["ai.labs.rag"]).toBe("rag");
  });

  it("maps legacy ai.labs.httpcalls → apicalls", () => {
    expect(EXTENSION_TO_RESOURCE_SLUG["ai.labs.httpcalls"]).toBe("apicalls");
  });

  it("hasResourceStore returns true for rag", () => {
    expect(hasResourceStore("ai.labs.rag")).toBe(true);
  });

  it("hasResourceStore returns true for snippet", () => {
    expect(hasResourceStore("ai.labs.snippet")).toBe(true);
  });

  it("hasResourceStore returns false for parser", () => {
    expect(hasResourceStore("ai.labs.parser")).toBe(false);
  });
});
