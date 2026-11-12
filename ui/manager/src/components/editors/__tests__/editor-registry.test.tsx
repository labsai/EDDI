import { describe, it, expect, vi } from "vitest";
import { isValidElement, type ReactElement } from "react";
import { EDITOR_MAP } from "@/components/editors/editor-registry";
import { RESOURCE_TYPES } from "@/lib/api/resources";
import { getResourceSlugForStep } from "@/lib/api/extensions";
import { RulesEditor } from "@/components/editors/rules-editor";
import { ApiCallsEditor } from "@/components/editors/apicalls-editor";
import { LlmEditor } from "@/components/editors/llm-editor";
import { OutputEditor } from "@/components/editors/output-editor";
import { PropertySetterEditor } from "@/components/editors/propertysetter-editor";
import { DictionaryEditor } from "@/components/editors/dictionary-editor";
import { McpCallsEditor } from "@/components/editors/mcpcalls-editor";
import { RagEditor } from "@/components/editors/rag-editor";
import { SnippetEditor } from "@/components/editors/snippet-editor";
import { ParserEditor } from "@/components/editors/parser-editor";

/** The editor component each resource slug must open — the registry's whole contract. */
const EXPECTED_EDITOR: Record<string, unknown> = {
  rules: RulesEditor,
  apicalls: ApiCallsEditor,
  llm: LlmEditor,
  output: OutputEditor,
  propertysetter: PropertySetterEditor,
  dictionary: DictionaryEditor,
  mcpcalls: McpCallsEditor,
  rag: RagEditor,
  snippets: SnippetEditor,
  parser: ParserEditor,
};

const META = { resourceId: "res-1", version: 3, isDirty: true };

type EditorProps = {
  data: unknown;
  onChange: (v: unknown) => void;
  readOnly: boolean;
  resourceId?: string;
  version?: number;
  isDirty?: boolean;
};

function renderEntry(slug: string, readOnly: boolean) {
  const data = { marker: slug };
  const onChange = vi.fn();
  const node = EDITOR_MAP[slug]!(data, onChange, readOnly, META);
  expect(isValidElement(node)).toBe(true);
  return { element: node as ReactElement<EditorProps>, data, onChange };
}

describe("editor-registry", () => {
  describe("EDITOR_MAP", () => {
    it("registers an editor for exactly the resource types the app knows", () => {
      expect(Object.keys(EDITOR_MAP).sort()).toEqual(
        RESOURCE_TYPES.map((rt) => rt.slug).sort(),
      );
    });

    it.each(Object.keys(EXPECTED_EDITOR))(
      "%s opens its own editor with the data, onChange and readOnly it is given",
      (slug) => {
        const { element, data, onChange } = renderEntry(slug, true);
        expect(element.type).toBe(EXPECTED_EDITOR[slug]);
        expect(element.props.data).toBe(data);
        expect(element.props.onChange).toBe(onChange);
        expect(element.props.readOnly).toBe(true);

        expect(renderEntry(slug, false).element.props.readOnly).toBe(false);
      },
    );

    it("passes the resource id, version and dirty flag through to the RAG editor", () => {
      const { element } = renderEntry("rag", false);
      expect(element.props.resourceId).toBe("res-1");
      expect(element.props.version).toBe(3);
      expect(element.props.isDirty).toBe(true);
    });
  });

  describe("getResourceSlugForStep — every step type the backend registers opens an editor", () => {
    /*
     * The step types the backend's bootstrap modules put into the
     * @LifecycleExtensions map (modules/<area>/bootstrap/*Module), each with a
     * config URI in the store its task reads, and the editor slug it must open.
     * Aliases are listed separately because workflows carry either spelling:
     * ApiCallsTask.ID is "ai.labs.httpcalls" (apicalls is the alias),
     * RulesEvaluationTask.ID is "ai.labs.behavior" (rules is the alias), and
     * LlmModule still registers "ai.labs.langchain".
     */
    const STORE_BACKED: Array<[string, string, string]> = [
      ["eddi://ai.labs.httpcalls", "eddi://ai.labs.apicalls/apicallstore/apicalls/a1?version=1", "apicalls"],
      ["eddi://ai.labs.apicalls", "eddi://ai.labs.apicalls/apicallstore/apicalls/a1?version=1", "apicalls"],
      ["eddi://ai.labs.behavior", "eddi://ai.labs.behavior/rulestore/rulesets/r1?version=1", "rules"],
      ["eddi://ai.labs.rules", "eddi://ai.labs.rules/rulestore/rulesets/r1?version=1", "rules"],
      ["eddi://ai.labs.llm", "eddi://ai.labs.llm/llmstore/llms/l1?version=1", "llm"],
      ["eddi://ai.labs.langchain", "eddi://ai.labs.langchain/llmstore/llms/l1?version=1", "llm"],
      ["eddi://ai.labs.property", "eddi://ai.labs.property/propertysetterstore/propertysetters/p1?version=1", "propertysetter"],
      ["eddi://ai.labs.output", "eddi://ai.labs.output/outputstore/outputsets/o1?version=1", "output"],
      ["eddi://ai.labs.mcpcalls", "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/m1?version=1", "mcpcalls"],
      ["eddi://ai.labs.rag", "eddi://ai.labs.rag/ragstore/rags/g1?version=1", "rag"],
      ["eddi://ai.labs.parser", "eddi://ai.labs.parser/parserstore/parsers/s1?version=1", "parser"],
    ];

    it.each(STORE_BACKED)("%s with a config URI opens the %s editor", (type, uri, slug) => {
      expect(getResourceSlugForStep({ type, config: { uri } })).toBe(slug);
      expect(EDITOR_MAP).toHaveProperty(slug);
    });

    it("decides by the URI's store, whichever spelling the step type has", () => {
      // An httpcalls step pointing at apicallstore, under a URI host that
      // names the alias — the store is the truth.
      expect(
        getResourceSlugForStep({
          type: "eddi://ai.labs.httpcalls",
          config: { uri: "eddi://ai.labs.apicalls/apicallstore/apicalls/6aed18496ddb7a7697be8?version=4" },
        }),
      ).toBe("apicalls");
      // An old-style type name whose URI points at the output store.
      expect(
        getResourceSlugForStep({
          type: "eddi://ai.labs.output.template",
          config: { uri: "eddi://ai.labs.output/outputstore/outputsets/o1?version=1" },
        }),
      ).toBe("output");
    });

    it("falls back to the step type when the URI has no store segment", () => {
      expect(
        getResourceSlugForStep({ type: "eddi://ai.labs.httpcalls", config: { uri: "eddi://ai.labs.httpcalls/a1?version=1" } }),
      ).toBe("apicalls");
      expect(getResourceSlugForStep({ type: "ai.labs.langchain", config: { uri: "a1" } })).toBe("llm");
    });

    it("resolves every store in RESOURCE_TYPES to its own slug", () => {
      for (const rt of RESOURCE_TYPES) {
        const uri = `eddi://${rt.extension}/${rt.store}/${rt.plural}/x1?version=1`;
        expect(getResourceSlugForStep({ type: "eddi://ai.labs.whatever", config: { uri } })).toBe(rt.slug);
      }
    });

    it.each([
      ["templating (inline, no resource)", { type: "eddi://ai.labs.templating", config: {} }],
      ["a parser with an embedded config", { type: "eddi://ai.labs.parser", config: { appendExpressions: true } }],
      ["an unknown type and store", { type: "eddi://ai.labs.unknown", config: { uri: "eddi://ai.labs.unknown/unknownstore/items/i1" } }],
    ])("has no editor slug for %s", (_name, step) => {
      expect(getResourceSlugForStep(step)).toBeUndefined();
    });
  });
});
