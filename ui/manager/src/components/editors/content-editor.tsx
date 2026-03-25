import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import Editor, { type OnMount } from "@monaco-editor/react";
import * as DialogPrimitive from "@radix-ui/react-dialog";
import { Maximize2, Minimize2 } from "lucide-react";
import { useTheme } from "@/components/layout/theme-provider";
import { cn } from "@/lib/utils";
import type { editor } from "monaco-editor";

// ─── Types ───────────────────────────────────────────────────────────────────

export interface ContentEditorProps {
  /** Current text value */
  value: string;
  /** Called on every content change */
  onChange: (value: string) => void;
  /** Whether the editor is read-only */
  readOnly?: boolean;
  /** Monaco language mode — defaults to "plaintext" */
  language?: "plaintext" | "json" | "markdown";
  /** Label shown in the fullscreen title bar */
  label?: string;
  /** Placeholder text shown when the editor is empty */
  placeholder?: string;
  /** Test ID for automated testing */
  testId?: string;
  /** Minimum number of visible lines (default: 4) */
  minLines?: number;
  /** Maximum number of visible lines in inline mode (default: 20) */
  maxLines?: number;
}

// ─── Constants ───────────────────────────────────────────────────────────────

const LINE_HEIGHT = 19; // px — Monaco default line height at fontSize 13
const PADDING_Y = 24; // px — top + bottom padding inside Monaco


// ─── Helpers ─────────────────────────────────────────────────────────────────

function countLines(text: string): number {
  if (!text) return 0;
  return text.split("\n").length;
}

function computeInlineHeight(
  text: string,
  minLines: number,
  maxLines: number
): number {
  const lines = Math.max(countLines(text), 1);
  const clampedLines = Math.min(Math.max(lines, minLines), maxLines);
  return clampedLines * LINE_HEIGHT + PADDING_Y;
}

/** Shared Monaco options for both inline and fullscreen modes */
function getBaseOptions(readOnly: boolean): editor.IStandaloneEditorConstructionOptions {
  return {
    readOnly,
    minimap: { enabled: false },
    fontSize: 13,
    lineNumbers: "on",
    wordWrap: "on",
    scrollBeyondLastLine: false,
    tabSize: 2,
    automaticLayout: true,
    folding: true,
    renderLineHighlight: "gutter",
    bracketPairColorization: { enabled: true },
    scrollbar: {
      verticalScrollbarSize: 8,
      horizontalScrollbarSize: 8,
    },
    padding: { top: 12, bottom: 12 },
    accessibilitySupport: "on",
  };
}

// ─── Status Bar ──────────────────────────────────────────────────────────────

function StatusBar({
  value,
  className,
}: {
  value: string;
  className?: string;
}) {
  const { t } = useTranslation();
  const lines = countLines(value);
  const chars = value.length;

  return (
    <div
      className={cn(
        "flex items-center justify-end gap-3 border-t border-border bg-muted/50 px-3 py-1 text-[11px] text-muted-foreground select-none",
        className
      )}
      aria-live="polite"
      aria-atomic="true"
    >
      <span>
        {lines} {t("contentEditor.lines", "lines")}
      </span>
      <span aria-hidden="true">·</span>
      <span>
        {chars.toLocaleString()} {t("contentEditor.chars", "chars")}
      </span>
    </div>
  );
}

// ─── Fullscreen Dialog ───────────────────────────────────────────────────────

function FullscreenEditor({
  open,
  onOpenChange,
  value,
  onChange,
  readOnly,
  language,
  label,
  testId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  value: string;
  onChange: (v: string) => void;
  readOnly: boolean;
  language: string;
  label?: string;
  testId?: string;
}) {
  const { resolvedTheme } = useTheme();
  const { t } = useTranslation();
  const fullscreenEditorRef = useRef<editor.IStandaloneCodeEditor | null>(null);

  const handleMount: OnMount = useCallback((ed) => {
    fullscreenEditorRef.current = ed;
    // Focus the editor on mount for immediate keyboard interaction
    ed.focus();
  }, []);

  const handleChange = useCallback(
    (val: string | undefined) => {
      if (val !== undefined) onChange(val);
    },
    [onChange]
  );

  // Trap Escape only when find widget is NOT active
  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Escape") {
        // Let Monaco's find widget handle Escape if it's open
        const findWidget = fullscreenEditorRef.current
          ?.getDomNode()
          ?.querySelector(".find-widget.visible");
        if (!findWidget) {
          e.preventDefault();
          onOpenChange(false);
        }
      }
    },
    [onOpenChange]
  );

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/60 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <DialogPrimitive.Content
          className={cn(
            "fixed z-50 flex flex-col bg-card shadow-2xl",
            "data-[state=open]:animate-in data-[state=closed]:animate-out",
            "data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0",
            "data-[state=closed]:zoom-out-[0.98] data-[state=open]:zoom-in-[0.98]",
            // Mobile: true fullscreen
            "inset-0",
            // Tablet: centered with margins
            "sm:inset-[5vh_2.5vw] sm:rounded-xl sm:border sm:border-border",
            // Desktop: comfortable centered panel
            "lg:inset-[7.5vh_5vw]"
          )}
          onKeyDown={handleKeyDown}
          aria-label={
            label ||
            t("contentEditor.fullscreenTitle", "Content Editor")
          }
          data-testid={testId ? `${testId}-fullscreen` : "content-editor-fullscreen"}
        >
          {/* Title bar */}
          <div className="flex items-center gap-3 border-b border-border px-4 py-3 shrink-0">
            <Minimize2 className="h-4 w-4 text-muted-foreground shrink-0" aria-hidden="true" />
            <DialogPrimitive.Title className="flex-1 truncate text-sm font-semibold text-foreground">
              {label || t("contentEditor.fullscreenTitle", "Content Editor")}
            </DialogPrimitive.Title>
            {readOnly && (
              <span className="rounded bg-muted px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("editor.readOnly", "Read-only")}
              </span>
            )}
            <DialogPrimitive.Close
              className={cn(
                "inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors",
                "bg-primary text-primary-foreground hover:bg-primary/90",
                "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              )}
              aria-label={t("contentEditor.close", "Done")}
            >
              {t("contentEditor.close", "Done")}
            </DialogPrimitive.Close>
          </div>

          {/* Hidden description for a11y */}
          <DialogPrimitive.Description className="sr-only">
            {t("contentEditor.fullscreenDescription", "Full-screen content editor. Press Escape to close.")}
          </DialogPrimitive.Description>

          {/* Editor body — fills remaining space */}
          <div className="flex-1 overflow-hidden">
            <Editor
              height="100%"
              language={language}
              theme={resolvedTheme === "dark" ? "vs-dark" : "vs"}
              value={value}
              onChange={handleChange}
              onMount={handleMount}
              options={{
                ...getBaseOptions(readOnly),
                lineNumbers: "on",
                minimap: { enabled: true },
                folding: true,
                find: {
                  addExtraSpaceOnTop: true,
                  autoFindInSelection: "never",
                  seedSearchStringFromSelection: "always",
                },
              }}
              loading={
                <div className="flex h-full items-center justify-center">
                  <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    {t("common.loading", "Loading...")}
                  </div>
                </div>
              }
            />
          </div>

          {/* Status bar */}
          <StatusBar value={value} className="shrink-0 rounded-b-xl" />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

/**
 * Rich content editor for long text fields (system prompts, HTTP bodies).
 *
 * Features:
 * - Inline Monaco editor with auto-sizing height
 * - Fullscreen expand mode via Radix Dialog
 * - Light/dark theme integration
 * - RTL-aware layout (logical properties throughout)
 * - Full i18n with react-i18next
 * - ARIA accessibility + keyboard navigation
 * - Responsive: mobile fullscreen, tablet/desktop centered panel
 */
export function ContentEditor({
  value,
  onChange,
  readOnly = false,
  language = "plaintext",
  label,
  placeholder,
  testId = "content-editor",
  minLines = 4,
  maxLines = 20,
}: ContentEditorProps) {
  const { resolvedTheme } = useTheme();
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const inlineEditorRef = useRef<editor.IStandaloneCodeEditor | null>(null);

  // Compute inline height based on content
  const inlineHeight = useMemo(
    () => computeInlineHeight(value, minLines, maxLines),
    [value, minLines, maxLines]
  );

  const handleMount: OnMount = useCallback((ed) => {
    inlineEditorRef.current = ed;
  }, []);

  const handleChange = useCallback(
    (val: string | undefined) => {
      if (val !== undefined) onChange(val);
    },
    [onChange]
  );

  // When closing fullscreen, refocus the inline expand button
  const expandButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!expanded && expandButtonRef.current) {
      expandButtonRef.current.focus();
    }
  }, [expanded]);

  // Keyboard shortcut: Ctrl/Cmd+Shift+F to toggle fullscreen
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.key === "f") {
        // Only trigger if the user is inside this editor
        const editorDom = inlineEditorRef.current?.getDomNode();
        if (editorDom?.contains(document.activeElement)) {
          e.preventDefault();
          setExpanded(true);
        }
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

  return (
    <div
      className="group relative"
      data-testid={testId}
    >
      {/* Inline editor container */}
      <div className="overflow-hidden rounded-lg border border-input transition-colors focus-within:border-ring focus-within:ring-1 focus-within:ring-ring">
        {/* Placeholder overlay */}
        {!value && placeholder && (
          <div
            className="pointer-events-none absolute inset-0 z-10 flex items-start px-14 pt-3 text-xs text-muted-foreground/60"
            aria-hidden="true"
          >
            {placeholder}
          </div>
        )}

        <Editor
          height={`${inlineHeight}px`}
          language={language}
          theme={resolvedTheme === "dark" ? "vs-dark" : "vs"}
          value={value}
          onChange={handleChange}
          onMount={handleMount}
          options={{
            ...getBaseOptions(readOnly),
            lineNumbers: "on",
            glyphMargin: false,
            lineDecorationsWidth: 4,
            lineNumbersMinChars: 3,
          }}
          loading={
            <div
              className="flex items-center justify-center text-xs text-muted-foreground"
              style={{ height: `${inlineHeight}px` }}
            >
              <div className="flex items-center gap-2">
                <div className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                {t("common.loading", "Loading...")}
              </div>
            </div>
          }
        />

        {/* Status bar + expand button */}
        <div className="flex items-center border-t border-border bg-muted/50">
          <StatusBar value={value} className="flex-1 border-t-0" />
          <button
            ref={expandButtonRef}
            type="button"
            onClick={() => setExpanded(true)}
            className={cn(
              "inline-flex items-center gap-1 px-2.5 py-1 text-[11px] font-medium text-muted-foreground",
              "transition-colors hover:text-foreground",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1",
              "opacity-0 group-hover:opacity-100 group-focus-within:opacity-100",
              "motion-safe:transition-opacity"
            )}
            aria-label={t("contentEditor.expand", "Expand editor")}
            title={t("contentEditor.expandHint", "Expand to fullscreen (Ctrl+Shift+F)")}
            data-testid={`${testId}-expand-btn`}
          >
            <Maximize2 className="h-3.5 w-3.5" aria-hidden="true" />
            <span className="hidden sm:inline">
              {t("contentEditor.expand", "Expand")}
            </span>
          </button>
        </div>
      </div>

      {/* Fullscreen dialog */}
      <FullscreenEditor
        open={expanded}
        onOpenChange={setExpanded}
        value={value}
        onChange={onChange}
        readOnly={readOnly}
        language={language}
        label={label}
        testId={testId}
      />
    </div>
  );
}
