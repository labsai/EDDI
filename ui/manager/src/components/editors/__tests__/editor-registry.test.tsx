import { describe, it, expect } from "vitest";
import { EDITOR_MAP, EXTENSION_TO_SLUG } from "@/components/editors/editor-registry";

describe("editor-registry", () => {
  describe("EDITOR_MAP", () => {
    it("has editor for rules", () => {
      expect(EDITOR_MAP.rules).toBeDefined();
      expect(typeof EDITOR_MAP.rules).toBe("function");
    });

    it("has editor for apicalls", () => {
      expect(EDITOR_MAP.apicalls).toBeDefined();
    });

    it("has editor for llm", () => {
      expect(EDITOR_MAP.llm).toBeDefined();
    });

    it("has editor for output", () => {
      expect(EDITOR_MAP.output).toBeDefined();
    });

    it("has editor for propertysetter", () => {
      expect(EDITOR_MAP.propertysetter).toBeDefined();
    });

    it("has editor for dictionary", () => {
      expect(EDITOR_MAP.dictionary).toBeDefined();
    });

    it("has editor for mcpcalls", () => {
      expect(EDITOR_MAP.mcpcalls).toBeDefined();
    });

    it("has editor for rag", () => {
      expect(EDITOR_MAP.rag).toBeDefined();
    });

    it("has editor for snippets", () => {
      expect(EDITOR_MAP.snippets).toBeDefined();
    });

    it("has exactly 9 editors registered", () => {
      expect(Object.keys(EDITOR_MAP).length).toBe(9);
    });
  });

  describe("EXTENSION_TO_SLUG", () => {
    it("maps rules extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.rules"]).toBe("rules");
    });

    it("maps apicalls extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.apicalls"]).toBe("apicalls");
    });

    it("maps llm extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.llm"]).toBe("llm");
    });

    it("maps output extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.output"]).toBe("output");
    });

    it("maps output.template extension type to output", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.output.template"]).toBe("output");
    });

    it("maps property extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.property"]).toBe("propertysetter");
    });

    it("maps mcpcalls extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.mcpcalls"]).toBe("mcpcalls");
    });

    it("maps dictionary extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.dictionary"]).toBe("dictionary");
    });

    it("maps rag extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.rag"]).toBe("rag");
    });

    it("maps snippets extension type", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.snippets"]).toBe("snippets");
    });

    it("returns undefined for unknown types", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.unknown"]).toBeUndefined();
    });
  });
});
