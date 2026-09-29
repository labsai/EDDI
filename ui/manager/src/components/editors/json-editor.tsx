import { useTheme } from "@/components/layout/theme-provider";
// Configures the self-hosted Monaco instance before <Editor> can look for one.
// Side-effect import: without it @monaco-editor/react falls back to the jsDelivr CDN.
import "@/lib/monaco-setup";
import Editor, { type OnMount, type BeforeMount, type Monaco } from "@monaco-editor/react";
import { useCallback, useEffect, useId, useRef } from "react";
import type { editor } from "monaco-editor";

/**
 * Every schema a mounted JsonEditor contributes, keyed by that editor's own
 * model path.
 *
 * Monaco's JSON diagnostics are one global setting: each
 * `setDiagnosticsOptions` call replaces the whole schema list. Registering a
 * single schema with `fileMatch: ["*"]` therefore validated *every* JSON model
 * against it — the snippet JSON tab against the LLM schema, the diff editor
 * against whatever editor mounted last — and the second editor to mount wiped
 * the first one's schema. Each editor now owns a unique path, contributes its
 * schema under that path only, and removes it on unmount.
 */
const schemasByModelPath = new Map<string, Record<string, unknown>>();

/**
 * Monaco is a page-wide singleton; the first `beforeMount` hands it over.
 * Kept here rather than per editor so an unmount can re-apply the list even
 * when it happens before that editor's own Monaco finished loading.
 */
let loadedMonaco: Monaco | null = null;

type JsonDefaults = {
  setDiagnosticsOptions: (options: Record<string, unknown>) => void;
};

function jsonDefaultsOf(monaco: Monaco | null): JsonDefaults | undefined {
  // `languages.json` is a Monaco *language contribution*, not part of the
  // core editor API — whether it is present depends on which Monaco entry
  // point the bundler resolved. Dereferencing it unconditionally threw
  // "Cannot read properties of undefined (reading 'jsonDefaults')" and,
  // because beforeMount runs during render, took the whole page down through
  // the top-level error boundary rather than costing one editor.
  //
  // Schema validation and autocomplete are an enhancement; syntax
  // highlighting and editing work without them. Degrade, don't crash.
  return (monaco as { languages?: { json?: { jsonDefaults?: JsonDefaults } } } | null)
    ?.languages?.json?.jsonDefaults;
}

function applySchemas() {
  const jsonDefaults = jsonDefaultsOf(loadedMonaco);
  if (!jsonDefaults) return;
  jsonDefaults.setDiagnosticsOptions({
    validate: true,
    allowComments: false,
    trailingCommas: "error",
    schemas: [...schemasByModelPath].map(([modelPath, schema]) => ({
      uri: `eddi://schema/${modelPath}`,
      fileMatch: [modelPath],
      schema,
    })),
  });
}

/** Records `schema` (or its absence) for one editor; re-applies only on a change. */
function syncSchema(modelPath: string, schema: object | undefined) {
  const current = schemasByModelPath.get(modelPath);
  if (current === schema || (!schema && current === undefined)) return;
  if (schema) {
    schemasByModelPath.set(modelPath, schema as Record<string, unknown>);
  } else {
    schemasByModelPath.delete(modelPath);
  }
  applySchemas();
}

export interface JsonEditorProps {
  /** Stringified JSON value */
  value: string;
  /** Called on every valid content change */
  onChange?: (value: string) => void;
  /** Disable editing */
  readOnly?: boolean;
  /** Editor height — defaults to 500px */
  height?: string;
  /** Test ID for integration testing */
  testId?: string;
  /** Optional JSON Schema object for validation and autocomplete */
  jsonSchema?: object;
}

/**
 * Monaco-based JSON editor with EDDI theme integration.
 * Provides syntax highlighting, validation, and auto-formatting.
 */
export function JsonEditor({
  value,
  onChange,
  readOnly = false,
  height = "500px",
  testId = "json-editor",
  jsonSchema,
}: JsonEditorProps) {
  const { resolvedTheme } = useTheme();
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  // Unique per mounted editor: the model's path is what the schema's
  // fileMatch is scoped to.
  const modelPath = `eddi-json-editor-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}.json`;

  const handleMount: OnMount = useCallback(
    (editor) => {
      editorRef.current = editor;
      // Auto-format on mount
      setTimeout(() => {
        editor.getAction("editor.action.formatDocument")?.run();
      }, 100);
    },
    []
  );

  const handleBeforeMount: BeforeMount = useCallback(
    (monaco) => {
      // Always re-applied here: this may be the call that first hands Monaco
      // over, after schemas were already recorded.
      loadedMonaco = monaco;
      if (jsonSchema) schemasByModelPath.set(modelPath, jsonSchema as Record<string, unknown>);
      applySchemas();
    },
    [jsonSchema, modelPath]
  );

  // Keep the registry in step with the prop after mount, and take this
  // editor's schema back out when it unmounts.
  useEffect(() => {
    syncSchema(modelPath, jsonSchema);
    return () => syncSchema(modelPath, undefined);
  }, [jsonSchema, modelPath]);

  const handleChange = useCallback(
    (val: string | undefined) => {
      if (val !== undefined && onChange) {
        onChange(val);
      }
    },
    [onChange]
  );

  return (
    <div data-testid={testId} className="overflow-hidden rounded-lg border border-border">
      <Editor
        height={height}
        language="json"
        path={modelPath}
        theme={resolvedTheme === "dark" ? "vs-dark" : "vs"}
        value={value}
        onChange={handleChange}
        beforeMount={handleBeforeMount}
        onMount={handleMount}
        options={{
          readOnly,
          minimap: { enabled: false },
          fontSize: 13,
          lineNumbers: "on",
          scrollBeyondLastLine: false,
          wordWrap: "on",
          tabSize: 2,
          automaticLayout: true,
          folding: true,
          bracketPairColorization: { enabled: true },
          renderLineHighlight: "gutter",
          scrollbar: {
            verticalScrollbarSize: 8,
            horizontalScrollbarSize: 8,
          },
          padding: { top: 12, bottom: 12 },
        }}
        loading={
          <div className="flex items-center justify-center" style={{ height }}>
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              Loading editor...
            </div>
          </div>
        }
      />
    </div>
  );
}
