import { useTheme } from "@/components/layout/theme-provider";
import Editor, { type OnMount, type BeforeMount } from "@monaco-editor/react";
import { useCallback, useRef } from "react";
import type { editor } from "monaco-editor";

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
      // Configure JSON schema for validation and autocomplete
      monaco.languages.json.jsonDefaults.setDiagnosticsOptions({
        validate: true,
        allowComments: false,
        trailingCommas: "error",
        schemas: jsonSchema
          ? [
              {
                uri: "eddi://schema/resource.json",
                fileMatch: ["*"],
                schema: jsonSchema as Record<string, unknown>,
              },
            ]
          : [],
      });
    },
    [jsonSchema]
  );

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
