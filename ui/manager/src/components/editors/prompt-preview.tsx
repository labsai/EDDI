import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import {
  Eye,
  RefreshCw,
  AlertTriangle,
  Copy,
  Check,
  ChevronDown,
  ChevronRight,
  Sparkles,
  Database,
} from "lucide-react";
import { useTemplatePreview } from "@/hooks/use-template-preview";
import { getConversationDescriptors, parseConversationUri } from "@/lib/api/conversations";
import { cn } from "@/lib/utils";

// ─── Types ───────────────────────────────────────────────────────────────────

interface PromptPreviewProps {
  /** The raw Qute template string */
  template: string;
}

// Variable group config for categorization
const VARIABLE_GROUPS: Record<string, { labelKey: string; fallback: string; color: string }> = {
  properties: { labelKey: "promptPreview.varProperties", fallback: "Properties", color: "text-emerald-600 dark:text-emerald-400" },
  memory: { labelKey: "promptPreview.varMemory", fallback: "Memory", color: "text-blue-600 dark:text-blue-400" },
  context: { labelKey: "promptPreview.varContext", fallback: "Context", color: "text-violet-600 dark:text-violet-400" },
  snippets: { labelKey: "promptPreview.varSnippets", fallback: "Snippets", color: "text-amber-600 dark:text-amber-400" },
  userInfo: { labelKey: "promptPreview.varUserInfo", fallback: "User Info", color: "text-cyan-600 dark:text-cyan-400" },
  conversationInfo: { labelKey: "promptPreview.varConversationInfo", fallback: "Conversation Info", color: "text-pink-600 dark:text-pink-400" },
  input: { labelKey: "promptPreview.varInput", fallback: "Input", color: "text-orange-600 dark:text-orange-400" },
  conversationLog: { labelKey: "promptPreview.varConversationLog", fallback: "Conversation Log", color: "text-gray-600 dark:text-gray-400" },
};

// ─── Main Component ──────────────────────────────────────────────────────────

export function PromptPreview({ template }: PromptPreviewProps) {
  const { t } = useTranslation();
  const [conversationId, setConversationId] = useState<string>("");
  const { mutate: preview, data, isPending, error: mutationError } =
    useTemplatePreview();

  // Fetch recent conversations for the picker
  const { data: conversations } = useQuery({
    queryKey: ["conversations", "preview-picker"],
    queryFn: () => getConversationDescriptors(20, 0, ""),
    staleTime: 30_000,
  });

  // Auto-preview on mount and when conversation changes
  const triggerPreview = useCallback(() => {
    if (template) {
      preview({
        template,
        conversationId: conversationId || undefined,
      });
    }
  }, [preview, template, conversationId]);

  useEffect(() => {
    triggerPreview();
  }, [triggerPreview]);

  const hasError = data?.error || mutationError;

  return (
    <div className="space-y-3" data-testid="prompt-preview">
      {/* Toolbar */}
      <div className="flex items-center gap-2 flex-wrap">
        <Database className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
        <select
          value={conversationId}
          onChange={(e) => setConversationId(e.target.value)}
          className="h-7 flex-1 min-w-0 rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid="preview-conversation-picker"
        >
          <option value="">
            {t("promptPreview.sampleData", "Sample data (no conversation)")}
          </option>
          {conversations?.map((conv) => {
            const id = parseConversationUri(conv.resource);
            return (
              <option key={id} value={id}>
                {conv.name || id.substring(0, 12)} — {conv.conversationState}
              </option>
            );
          })}
        </select>
        <button
          type="button"
          onClick={triggerPreview}
          disabled={isPending}
          className={cn(
            "inline-flex items-center gap-1 rounded-md border border-input px-2 py-1 text-xs font-medium text-foreground transition-colors hover:bg-secondary",
            isPending && "opacity-50"
          )}
          data-testid="preview-refresh"
        >
          <RefreshCw className={cn("h-3 w-3", isPending && "animate-spin")} />
          {t("promptPreview.refresh", "Refresh")}
        </button>
      </div>

      {/* Disclaimer */}
      <div className="flex items-start gap-2 rounded-md bg-primary/5 px-3 py-2 text-[11px] text-muted-foreground">
        <Sparkles className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary/60" />
        <span>
          {conversationId
            ? t(
                "promptPreview.disclaimerReal",
                "Preview uses the real Qute engine with actual conversation data."
              )
            : t(
                "promptPreview.disclaimerSample",
                "Preview uses the real Qute engine with built-in sample data. Select a conversation for real values."
              )}
        </span>
      </div>

      {/* Error state */}
      {hasError && (
        <div
          className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive"
          data-testid="preview-error"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <div>
            <p className="font-medium">
              {t("promptPreview.error", "Template resolution error")}
            </p>
            <pre className="mt-1 text-[10px] font-mono whitespace-pre-wrap break-all opacity-80">
              {data?.error || (mutationError as Error)?.message}
            </pre>
          </div>
        </div>
      )}

      {/* Resolved prompt */}
      {data?.resolved != null && (
        <ResolvedPromptView
          original={template}
          resolved={data.resolved}
        />
      )}

      {/* Loading state */}
      {isPending && !data && (
        <div className="flex items-center justify-center py-8">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            {t("promptPreview.loading", "Resolving template…")}
          </div>
        </div>
      )}

      {/* Variable Reference Panel */}
      {data && (
        <VariableReferencePanel
          availableVariables={data.availableVariables}
          variableValues={data.variableValues}
        />
      )}
    </div>
  );
}

// ─── Resolved Prompt View ────────────────────────────────────────────────────

function ResolvedPromptView({
  original,
  resolved,
}: {
  original: string;
  resolved: string;
}) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(resolved);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard not available
    }
  };

  // Highlight resolved values by finding Qute expressions in the original
  // and wrapping their resolved counterparts in the output
  const highlightedHtml = useMemo(
    () => buildHighlightedPreview(original, resolved),
    [original, resolved]
  );

  return (
    <div
      className="rounded-lg border border-border bg-card"
      data-testid="resolved-prompt"
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-border px-3 py-2">
        <div className="flex items-center gap-1.5">
          <Eye className="h-3.5 w-3.5 text-primary" />
          <span className="text-xs font-semibold text-foreground">
            {t("promptPreview.resolvedPrompt", "Resolved Prompt")}
          </span>
        </div>
        <button
          type="button"
          onClick={handleCopy}
          className="inline-flex items-center gap-1 rounded px-2 py-0.5 text-[10px] font-medium text-muted-foreground transition-colors hover:text-foreground hover:bg-secondary"
          data-testid="preview-copy"
        >
          {copied ? (
            <Check className="h-3 w-3 text-emerald-500" />
          ) : (
            <Copy className="h-3 w-3" />
          )}
          {copied
            ? t("promptPreview.copied", "Copied!")
            : t("promptPreview.copy", "Copy")}
        </button>
      </div>

      {/* Rendered content */}
      <div
        className="px-4 py-3 text-xs leading-relaxed text-foreground/90 font-mono whitespace-pre-wrap break-words max-h-[400px] overflow-y-auto"
        dangerouslySetInnerHTML={{ __html: highlightedHtml }}
        data-testid="resolved-prompt-content"
      />
    </div>
  );
}

// ─── Variable Reference Panel ────────────────────────────────────────────────

function VariableReferencePanel({
  availableVariables,
  variableValues,
}: {
  availableVariables: string[];
  variableValues: Record<string, unknown>;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [copiedVar, setCopiedVar] = useState<string | null>(null);

  // Group variables by top-level prefix
  const groups = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const v of availableVariables) {
      const dot = v.indexOf(".");
      const prefix = dot > 0 ? v.substring(0, dot) : v;
      if (!map.has(prefix)) map.set(prefix, []);
      map.get(prefix)!.push(v);
    }
    return map;
  }, [availableVariables]);

  const handleCopyVariable = async (varName: string) => {
    try {
      await navigator.clipboard.writeText(`{${varName}}`);
      setCopiedVar(varName);
      setTimeout(() => setCopiedVar(null), 1500);
    } catch {
      // Clipboard not available
    }
  };

  return (
    <div className="rounded-lg border border-border" data-testid="variable-reference">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center gap-2 px-3 py-2 text-start text-xs font-semibold text-muted-foreground hover:text-foreground transition-colors"
      >
        {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        {t("promptPreview.availableVariables", "Available Variables")}
        <span className="rounded-full bg-muted px-1.5 py-0.5 text-[10px] font-medium">
          {availableVariables.length}
        </span>
      </button>

      {open && (
        <div className="border-t border-border px-3 py-2 space-y-3">
          {Array.from(groups.entries()).map(([prefix, vars]) => {
            const config = VARIABLE_GROUPS[prefix] ?? {
              labelKey: "",
              fallback: prefix,
              color: "text-foreground",
            };
            return (
              <div key={prefix}>
                <div className={cn("text-[10px] font-bold uppercase tracking-wider mb-1", config.color)}>
                  {t(config.labelKey, config.fallback)}
                </div>
                <div className="flex flex-wrap gap-1">
                  {vars.map((v) => {
                    const val = variableValues[v];
                    const isCopied = copiedVar === v;
                    return (
                      <button
                        key={v}
                        type="button"
                        onClick={() => handleCopyVariable(v)}
                        title={val != null ? `${v} = ${String(val)}` : v}
                        className={cn(
                          "inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-[10px] font-mono transition-all",
                          "border border-border bg-muted/50 text-foreground/80",
                          "hover:bg-primary/10 hover:border-primary/30 hover:text-primary",
                          isCopied && "bg-emerald-500/10 border-emerald-500/30 text-emerald-600 dark:text-emerald-400"
                        )}
                        data-testid={`var-chip-${v}`}
                      >
                        {isCopied ? (
                          <Check className="h-2.5 w-2.5" />
                        ) : null}
                        {`{${v}}`}
                      </button>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ─── Highlight Helpers ───────────────────────────────────────────────────────

/**
 * Build an HTML string that shows the resolved prompt with Qute expression
 * resolutions highlighted. Works by finding `{expression}` patterns in the
 * original template and matching them to the resolved output.
 *
 * This is a heuristic approach that handles the common cases: simple variables,
 * {#if}/{#for} blocks, and multi-line templates.
 */
function buildHighlightedPreview(original: string, resolved: string): string {
  if (!original || !resolved) {
    return escapeHtml(resolved || "");
  }

  // Find all Qute expression positions in the original
  const qutePattern = /\{[a-zA-Z_#/!|][^}]*\}/g;
  const hasQuteExpressions = qutePattern.test(original);

  if (!hasQuteExpressions) {
    // No templates — just show the resolved text
    return escapeHtml(resolved);
  }

  // Strategy: split the original template on static text fragments,
  // find them in the resolved output, and highlight the gaps (= resolved values)
  const staticParts = original.split(/\{[a-zA-Z_#/!|][^}]*\}/);
  const cleanParts = staticParts.map((p) => p.trim()).filter(Boolean);

  if (cleanParts.length === 0) {
    // Entire template is expressions
    return `<mark class="rounded bg-primary/15 px-0.5 text-primary font-semibold">${escapeHtml(resolved)}</mark>`;
  }

  // Simple approach: highlight any part of the resolved text that doesn't
  // appear verbatim in the original template
  let result = resolved;
  const highlights: Array<{ start: number; end: number }> = [];

  // Find static anchors in the resolved text
  let searchFrom = 0;
  const anchors: Array<{ text: string; pos: number }> = [];
  for (const part of cleanParts) {
    const idx = result.indexOf(part, searchFrom);
    if (idx >= 0) {
      anchors.push({ text: part, pos: idx });
      searchFrom = idx + part.length;
    }
  }

  // The gaps between static anchors are resolved values
  if (anchors.length > 0) {
    let pos = 0;
    for (const anchor of anchors) {
      if (anchor.pos > pos) {
        highlights.push({ start: pos, end: anchor.pos });
      }
      pos = anchor.pos + anchor.text.length;
    }
    if (pos < result.length) {
      highlights.push({ start: pos, end: result.length });
    }
  }

  // Build the HTML with highlights
  if (highlights.length === 0) {
    return escapeHtml(resolved);
  }

  let html = "";
  let lastEnd = 0;
  for (const h of highlights) {
    // Static text before highlight
    html += escapeHtml(result.substring(lastEnd, h.start));
    // Highlighted (resolved) value
    const value = result.substring(h.start, h.end);
    if (value.trim()) {
      html += `<mark class="rounded bg-primary/15 px-0.5 text-primary font-semibold">${escapeHtml(value)}</mark>`;
    } else {
      html += escapeHtml(value);
    }
    lastEnd = h.end;
  }
  html += escapeHtml(result.substring(lastEnd));

  return html;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
