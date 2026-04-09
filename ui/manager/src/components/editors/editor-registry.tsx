/**
 * Centralized editor registry — single source of truth for
 * resource-type → form editor mapping.
 *
 * Used by both ResourceDetailPage and StudioEditorPanel.
 * When adding a new editor, add it HERE only.
 */
import type { ReactNode } from "react";
import {
  RulesEditor,
  type RulesConfig,
} from "@/components/editors/rules-editor";
import {
  LlmEditor,
  type LlmConfig,
} from "@/components/editors/llm-editor";
import {
  ApiCallsEditor,
  type HttpCallsConfig as ApiCallsConfig,
} from "@/components/editors/apicalls-editor";
import {
  OutputEditor,
  type OutputConfig,
} from "@/components/editors/output-editor";
import {
  PropertySetterEditor,
  type PropertySetterConfig,
} from "@/components/editors/propertysetter-editor";
import {
  DictionaryEditor,
  type DictionaryConfig,
} from "@/components/editors/dictionary-editor";
import {
  McpCallsEditor,
  type McpCallsConfig,
} from "@/components/editors/mcpcalls-editor";
import {
  RagEditor,
  type RagConfig,
} from "@/components/editors/rag-editor";
import {
  SnippetEditor,
  type PromptSnippetConfig,
} from "@/components/editors/snippet-editor";

export type EditorRenderFn = (
  parsed: unknown,
  onChange: (val: unknown) => void,
  readOnly: boolean,
  meta: { resourceId: string; version: number },
) => ReactNode;

/**
 * Maps resource type slugs to their form editor render functions.
 * Used by ConfigEditorLayout's `renderFormEditor` prop.
 */
export const EDITOR_MAP: Record<string, EditorRenderFn> = {
  rules: (p, o, r) => (
    <RulesEditor data={p as RulesConfig} onChange={o} readOnly={r} />
  ),
  apicalls: (p, o, r) => (
    <ApiCallsEditor data={p as ApiCallsConfig} onChange={o} readOnly={r} />
  ),
  llm: (p, o, r) => (
    <LlmEditor data={p as LlmConfig} onChange={o} readOnly={r} />
  ),
  output: (p, o, r) => (
    <OutputEditor data={p as OutputConfig} onChange={o} readOnly={r} />
  ),
  propertysetter: (p, o, r) => (
    <PropertySetterEditor data={p as PropertySetterConfig} onChange={o} readOnly={r} />
  ),
  dictionary: (p, o, r) => (
    <DictionaryEditor data={p as DictionaryConfig} onChange={o} readOnly={r} />
  ),
  mcpcalls: (p, o, r) => (
    <McpCallsEditor data={p as McpCallsConfig} onChange={o} readOnly={r} />
  ),
  rag: (p, o, r, meta) => (
    <RagEditor data={p as RagConfig} onChange={o} readOnly={r} resourceId={meta.resourceId} version={meta.version} />
  ),
  snippets: (p, o, r) => (
    <SnippetEditor data={p as PromptSnippetConfig} onChange={o} readOnly={r} />
  ),
};

/**
 * Maps EDDI extension type strings to resource slug identifiers.
 * Used by StudioEditorPanel to resolve pipeline stage types.
 */
export const EXTENSION_TO_SLUG: Record<string, string> = {
  "ai.labs.rules": "rules",
  "ai.labs.apicalls": "apicalls",
  "ai.labs.llm": "llm",
  "ai.labs.output": "output",
  "ai.labs.output.template": "output",
  "ai.labs.property": "propertysetter",
  "ai.labs.mcpcalls": "mcpcalls",
  "ai.labs.dictionary": "dictionary",
  "ai.labs.parser": "dictionary",
  "ai.labs.rag": "rag",
  "ai.labs.snippets": "snippets",
};
