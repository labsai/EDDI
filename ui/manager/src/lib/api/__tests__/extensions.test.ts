import { describe, it, expect } from "vitest";
import { Puzzle } from "lucide-react";
import { RESOURCE_TYPES } from "../resources";
import {
  EXTENSION_TYPE_INFO,
  EXTENSION_TO_RESOURCE_SLUG,
  hasResourceStore,
  getExtensionLabel,
  getExtensionIcon,
  getExtensionColor,
  getExtensionTypeConfig,
  getExtensionSortOrder,
  sortExtensionTypes,
} from "../extensions";

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

describe("EXTENSION_TYPE_INFO — data integrity", () => {
  const iconMapKeys = ["FileText", "GitBranch", "Globe", "Brain", "MessageSquareText", "Settings", "FileCode", "Plug"];

  it("every entry has valid fields, unique order, eddi:// key, and known icon", () => {
    const orders = new Set<number>();
    for (const [key, config] of Object.entries(EXTENSION_TYPE_INFO)) {
      expect(key).toMatch(/^eddi:\/\//);
      expect(config.label.length).toBeGreaterThan(0);
      expect(config.order).toBeGreaterThanOrEqual(1);

      // Ensure uniqueness of orders
      expect(orders).not.toContain(config.order);
      orders.add(config.order);

      expect(iconMapKeys).toContain(config.icon);
    }
  });
});

describe("Fallbacks for unknown / bare-prefix inputs", () => {
  const knownPrefixed = Object.keys(EXTENSION_TYPE_INFO)[0]!;
  const knownBare = knownPrefixed.replace("eddi://", "");

  it("getExtensionLabel returns raw type for unknown", () => {
    expect(getExtensionLabel("eddi://unknown")).toBe("eddi://unknown");
  });

  it("getExtensionIcon returns Puzzle for unknown, resolves for known", () => {
    expect(getExtensionIcon("eddi://unknown")).toBe(Puzzle);
    expect(getExtensionIcon(knownPrefixed)).not.toBe(Puzzle);
    expect(getExtensionIcon(knownBare)).not.toBe(Puzzle);
  });

  it("getExtensionColor returns text-gray-400 for unknown, colored for known", () => {
    expect(getExtensionColor("eddi://unknown")).toBe("text-gray-400");
    expect(getExtensionColor(knownPrefixed)).not.toBe("text-gray-400");
    expect(getExtensionColor(knownBare)).not.toBe("text-gray-400");
  });

  it("getExtensionTypeConfig returns Puzzle + gray for unknown", () => {
    const fb = getExtensionTypeConfig("eddi://unknown");
    expect(fb.icon).toBe(Puzzle);
    expect(fb.color).toBe("text-gray-400");
  });

  it("getExtensionTypeConfig extracts last segment as label for unknown", () => {
    expect(getExtensionTypeConfig("some.random.Foo").label).toBe("Foo");
  });

  it("getExtensionSortOrder returns 99 for unknown", () => {
    expect(getExtensionSortOrder("eddi://unknown")).toBe(99);
    expect(getExtensionSortOrder("ai.labs.unknown")).toBe(99);
  });

  it("getExtensionSortOrder works with bare prefix", () => {
    expect(getExtensionSortOrder(knownBare)).toBe(getExtensionSortOrder(knownPrefixed));
  });
});

describe("sortExtensionTypes", () => {
  it("does not mutate and places unknown last", () => {
    const items = [
      { type: "eddi://ai.labs.llm" },
      { type: "eddi://ai.labs.unknown" },
    ];
    const snapshot = [...items];
    const sorted = sortExtensionTypes(items);
    expect(items).toEqual(snapshot);
    expect(sorted[0]!.type).toBe("eddi://ai.labs.llm");
    expect(sorted[1]!.type).toBe("eddi://ai.labs.unknown");
  });
});

describe("EXTENSION_TO_RESOURCE_SLUG — cross-reference with EXTENSION_TYPE_INFO", () => {
  // snippet/snippets has no EXTENSION_TYPE_INFO entry (no pipeline editor for snippets)
  const SKIP = new Set(["ai.labs.snippet", "ai.labs.snippets"]);

  it("all resource-slug extensions (except snippets) have matching EXTENSION_TYPE_INFO entries", () => {
    for (const [ext, slug] of Object.entries(EXTENSION_TO_RESOURCE_SLUG)) {
      if (SKIP.has(ext)) continue;
      const prefixed = "eddi://" + ext;
      expect(
        EXTENSION_TYPE_INFO[prefixed],
        `${ext} → ${slug} has no matching EXTENSION_TYPE_INFO entry (expected key "${prefixed}")`,
      ).toBeDefined();
    }
  });
});
