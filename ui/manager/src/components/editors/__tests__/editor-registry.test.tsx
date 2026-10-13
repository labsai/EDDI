import { describe, it, expect, vi } from "vitest";
import { isValidElement, type ReactElement } from "react";
import { EDITOR_MAP, EXTENSION_TO_SLUG } from "@/components/editors/editor-registry";
import { RESOURCE_TYPES } from "@/lib/api/resources";
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

  describe("EXTENSION_TO_SLUG", () => {
    it("maps every workflow-step resource type's own extension back to its slug", () => {
      for (const rt of RESOURCE_TYPES) {
        expect(EXTENSION_TO_SLUG[`eddi://${rt.extension}`]).toBe(rt.slug);
      }
    });

    it("only ever resolves to a slug that has an editor", () => {
      for (const slug of Object.values(EXTENSION_TO_SLUG)) {
        expect(EDITOR_MAP).toHaveProperty(slug);
      }
    });

    it.each([
      ["eddi://ai.labs.behavior", "rules"],
      ["eddi://ai.labs.output.template", "output"],
    ])("maps the alias %s to %s", (extension, slug) => {
      expect(EXTENSION_TO_SLUG[extension]).toBe(slug);
    });

    it("returns undefined for unknown types", () => {
      expect(EXTENSION_TO_SLUG["eddi://ai.labs.unknown"]).toBeUndefined();
    });
  });
});
