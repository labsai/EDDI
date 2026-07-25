import { useState, useMemo, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";
import {
  getDetailedConversation,
  type DetailedConversationStep,
} from "@/lib/api/conversations";
import {
  Database,
  RefreshCw,
  AlertTriangle,
  Search,
  Copy,
  Check,
  ChevronDown,
  ChevronRight,
  Maximize2,
  Minimize2,
} from "lucide-react";

// ==================== Main Component ====================

interface MemoryInspectorProps {
  conversationId: string | null;
}

export function MemoryInspector({ conversationId }: MemoryInspectorProps) {
  const { t } = useTranslation();
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedTab, setSelectedTab] = useState<number | "props">(0);
  const [expandAll, setExpandAll] = useState(false);

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ["memory", "detailed", conversationId],
    queryFn: () => getDetailedConversation(conversationId!),
    enabled: !!conversationId,
    staleTime: 10_000,
  });

  if (!conversationId) {
    return (
      <EmptyState
        message={t(
          "memoryInspector.empty",
          "No memory data — send a message to populate conversation memory",
        )}
      />
    );
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <RefreshCw className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (isError && !data) {
    return (
      <div className="flex flex-col items-center gap-2 py-6 text-center" data-testid="memory-inspector-error">
        <AlertTriangle className="h-8 w-8 text-destructive/50" />
        <p className="text-sm text-muted-foreground">
          {t("memoryInspector.error", "Failed to load memory data")}
        </p>
      </div>
    );
  }

  const steps = data?.conversationSteps ?? [];
  const properties = data?.conversationProperties ?? {};
  const hasProperties = Object.keys(properties).length > 0;

  // Make sure selectedTab is valid
  const currentTab =
    selectedTab === "props"
      ? (hasProperties ? "props" : 0)
      : (steps.length === 0 && hasProperties
        ? "props"
        : (selectedTab < steps.length ? selectedTab : 0));

  return (
    <div className="flex flex-col gap-3 p-3" data-testid="memory-inspector">
      {/* Header (Search + Expand/Collapse All + Refresh) */}
      <div className="flex items-center justify-between gap-2">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute start-2.5 top-1.5 h-3.5 w-3.5 text-muted-foreground" />
          <input
            type="text"
            placeholder={t("memoryInspector.search", "Search keys or values...")}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="h-7 w-full rounded-md border border-input bg-card ps-8 pe-3 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={() => setExpandAll(!expandAll)}
            className="flex h-7 px-2 items-center gap-1 rounded-md border border-input text-[11px] font-medium text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            title={expandAll ? t("memoryInspector.collapseAll", "Collapse all") : t("memoryInspector.expandAll", "Expand all")}
          >
            {expandAll ? (
              <>
                <Minimize2 className="h-3 w-3" />
                <span>{t("memoryInspector.collapseAll", "Collapse all")}</span>
              </>
            ) : (
              <>
                <Maximize2 className="h-3 w-3" />
                <span>{t("memoryInspector.expandAll", "Expand all")}</span>
              </>
            )}
          </button>
          <button
            onClick={() => refetch()}
            className="flex h-7 w-7 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            title={t("common.retry", "Refresh")}
            data-testid="memory-refresh"
          >
            <RefreshCw className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {steps.length === 0 && !hasProperties ? (
        <EmptyState
          message={t(
            "memoryInspector.empty",
            "No memory data — send a message to populate conversation memory",
          )}
        />
      ) : (
        <>
          {/* Tabs */}
          <div className="flex flex-wrap gap-1.5 border-b border-border pb-2">
            {steps.map((_step, idx) => (
              <button
                key={idx}
                onClick={() => setSelectedTab(idx)}
                className={cn(
                  "rounded-full px-3 py-1 text-xs font-medium transition-colors",
                  currentTab === idx
                    ? "bg-primary text-primary-foreground shadow-sm"
                    : "bg-muted/50 text-muted-foreground hover:bg-muted hover:text-foreground"
                )}
              >
                {t("memoryInspector.step", "Step")} {idx + 1}
              </button>
            ))}
            {hasProperties && (
              <button
                onClick={() => setSelectedTab("props")}
                className={cn(
                  "rounded-full px-3 py-1 text-xs font-medium transition-colors",
                  currentTab === "props"
                    ? "bg-primary text-primary-foreground shadow-sm"
                    : "bg-muted/50 text-muted-foreground hover:bg-muted hover:text-foreground"
                )}
              >
                {t("memoryInspector.properties", "Properties")}
              </button>
            )}
          </div>

          {/* Content */}
          <div className="flex flex-col gap-2">
            {(() => {
              if (currentTab === "props") {
                return <PropertiesTable properties={properties} searchQuery={searchQuery} expandAll={expandAll} />;
              }
              const activeStep = steps[currentTab as number];
              return activeStep ? (
                <StepTable step={activeStep} searchQuery={searchQuery} expandAll={expandAll} />
              ) : null;
            })()}
          </div>
        </>
      )}
    </div>
  );
}

// ==================== Step Table ====================

function StepTable({
  step,
  searchQuery,
  expandAll,
}: {
  step: DetailedConversationStep;
  searchQuery: string;
  expandAll: boolean;
}) {
  const { t } = useTranslation();

  const items = useMemo(() => {
    let filtered = step.conversationStep ?? [];
    if (searchQuery) {
      const lowerQ = searchQuery.toLowerCase();
      filtered = filtered.filter(
        (item) =>
          item.key.toLowerCase().includes(lowerQ) ||
          String(item.value).toLowerCase().includes(lowerQ) ||
          (typeof item.value === "object" &&
            JSON.stringify(item.value).toLowerCase().includes(lowerQ))
      );
    }
    return filtered;
  }, [step.conversationStep, searchQuery]);

  if (items.length === 0) {
    return (
      <div className="py-6 text-center text-xs text-muted-foreground">
        {t("memoryInspector.noMatches", "No matching data found")}
      </div>
    );
  }

  return (
    <div className="rounded-lg border border-border bg-card overflow-hidden shadow-xs">
      <div className="flex items-center justify-between border-b border-border bg-muted/40 px-3 py-2">
        <span className="text-xs font-semibold text-foreground">
          {t("memoryInspector.stepData", "Step Data")}
        </span>
        <span className="text-[10px] font-medium text-muted-foreground rounded-full bg-muted px-2 py-0.5 border border-border/50">
          {items.length} {t("memoryInspector.keys", "keys")}
        </span>
      </div>
      <div className="divide-y divide-border/60">
        {items.map((item) => (
          <KeyValueRow
            key={item.key}
            itemKey={item.key}
            itemValue={item.value}
            forceExpand={expandAll}
          />
        ))}
      </div>
    </div>
  );
}

// ==================== Properties Table ====================

function PropertiesTable({
  properties,
  searchQuery,
  expandAll,
}: {
  properties: Record<string, unknown>;
  searchQuery: string;
  expandAll: boolean;
}) {
  const { t } = useTranslation();

  const { systemProps, customProps } = useMemo(() => {
    const sys: Record<string, unknown> = {};
    const custom: Record<string, unknown> = {};

    for (const [k, v] of Object.entries(properties)) {
      if (searchQuery) {
        const lowerQ = searchQuery.toLowerCase();
        const matches =
          k.toLowerCase().includes(lowerQ) ||
          String(v).toLowerCase().includes(lowerQ) ||
          (typeof v === "object" &&
            JSON.stringify(v).toLowerCase().includes(lowerQ));
        if (!matches) continue;
      }

      if (k.startsWith("_") || k === "id" || k === "conversationId" || k.startsWith("system")) {
        sys[k] = v;
      } else {
        custom[k] = v;
      }
    }

    return { systemProps: sys, customProps: custom };
  }, [properties, searchQuery]);

  const hasSystem = Object.keys(systemProps).length > 0;
  const hasCustom = Object.keys(customProps).length > 0;

  if (!hasSystem && !hasCustom) {
    return (
      <div className="py-6 text-center text-xs text-muted-foreground">
        {t("memoryInspector.noMatches", "No matching data found")}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {hasCustom && (
        <PropertyGroup
          title={t("memoryInspector.customProperties", "Custom Properties")}
          props={customProps}
          expandAll={expandAll}
        />
      )}
      {hasSystem && (
        <PropertyGroup
          title={t("memoryInspector.systemProperties", "System Properties")}
          props={systemProps}
          expandAll={expandAll}
        />
      )}
    </div>
  );
}

function PropertyGroup({
  title,
  props,
  expandAll,
}: {
  title: string;
  props: Record<string, unknown>;
  expandAll: boolean;
}) {
  const { t } = useTranslation();
  const entries = Object.entries(props);
  return (
    <div className="rounded-lg border border-border bg-card overflow-hidden shadow-xs">
      <div className="flex items-center justify-between border-b border-border bg-muted/40 px-3 py-2">
        <span className="text-xs font-semibold text-foreground">{title}</span>
        <span className="text-[10px] font-medium text-muted-foreground rounded-full bg-muted px-2 py-0.5 border border-border/50">
          {entries.length} {t("memoryInspector.keys", "keys")}
        </span>
      </div>
      <div className="divide-y divide-border/60">
        {entries.map(([k, v]) => (
          <KeyValueRow
            key={k}
            itemKey={k}
            itemValue={v}
            forceExpand={expandAll}
          />
        ))}
      </div>
    </div>
  );
}

// ==================== Shared KeyValueRow Component ====================

function KeyValueRow({
  itemKey,
  itemValue,
  forceExpand = false,
}: {
  itemKey: string;
  itemValue: unknown;
  forceExpand?: boolean;
}) {
  const { t } = useTranslation();
  const [localExpanded, setLocalExpanded] = useState(false);
  const [copiedKey, setCopiedKey] = useState(false);
  const [copiedVal, setCopiedVal] = useState(false);

  const expanded = forceExpand || localExpanded;

  const type = getValueType(itemValue);
  const formattedJson = useMemo(() => {
    try {
      return JSON.stringify(itemValue, null, 2);
    } catch {
      return String(itemValue);
    }
  }, [itemValue]);

  const previewText = useMemo(() => formatInlinePreview(itemValue), [itemValue]);

  const copyKey = useCallback(
    async (e: React.MouseEvent) => {
      e.stopPropagation();
      try {
        await navigator.clipboard.writeText(itemKey);
        setCopiedKey(true);
        setTimeout(() => setCopiedKey(false), 1500);
      } catch {
        /* ignore */
      }
    },
    [itemKey],
  );

  const copyVal = useCallback(
    async (e: React.MouseEvent) => {
      e.stopPropagation();
      try {
        await navigator.clipboard.writeText(formattedJson);
        setCopiedVal(true);
        setTimeout(() => setCopiedVal(false), 1500);
      } catch {
        /* ignore */
      }
    },
    [formattedJson],
  );

  return (
    <div
      onClick={() => setLocalExpanded(!localExpanded)}
      className="group flex flex-col p-2.5 hover:bg-muted/30 transition-colors cursor-pointer"
    >
      {/* Top Row: Key Name, Type Badge, Inline Preview, Copy Buttons */}
      <div className="flex items-center gap-2 min-w-0">
        {/* Toggle chevron */}
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            setLocalExpanded(!localExpanded);
          }}
          className="shrink-0 text-muted-foreground hover:text-foreground"
          aria-expanded={expanded}
        >
          {expanded ? (
            <ChevronDown className="h-3.5 w-3.5 text-primary" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground/60" />
          )}
        </button>

        {/* Key Name */}
        <div className="flex items-center gap-1.5 max-w-[40%] min-w-0 shrink-0">
          <span className="truncate text-xs font-mono font-semibold text-foreground" title={itemKey}>
            {itemKey}
          </span>
          <TypeBadge type={type} />
        </div>

        {/* Inline Value Preview (High Contrast) */}
        {!expanded && (
          <div className="flex-1 min-w-0 ps-2">
            <span
              className={cn(
                "truncate text-xs font-mono block",
                type === "string" && "text-emerald-600 dark:text-emerald-400 font-normal",
                type === "number" && "text-amber-600 dark:text-amber-400 font-semibold",
                type === "boolean" && "text-blue-600 dark:text-blue-400 font-semibold",
                type === "array" && "text-purple-600 dark:text-purple-400",
                type === "object" && "text-indigo-600 dark:text-indigo-400",
                (type === "null" || type === "undefined") && "text-muted-foreground/50 italic",
              )}
              title={previewText}
            >
              {previewText}
            </span>
          </div>
        )}

        {/* Actions (Copy Key / Copy Value) */}
        <div className="ms-auto flex items-center gap-1 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
          <button
            type="button"
            onClick={copyKey}
            className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-mono text-muted-foreground hover:bg-muted hover:text-foreground border border-border/40 transition-colors"
            title={t("memoryInspector.copyKey", "Copy key")}
          >
            {copiedKey ? (
              <Check className="h-3 w-3 text-emerald-500" />
            ) : (
              <span>Key</span>
            )}
          </button>

          <button
            type="button"
            onClick={copyVal}
            className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-mono text-muted-foreground hover:bg-muted hover:text-foreground border border-border/40 transition-colors"
            title={t("memoryInspector.copyValue", "Copy value")}
          >
            {copiedVal ? (
              <Check className="h-3 w-3 text-emerald-500" />
            ) : (
              <Copy className="h-3 w-3" />
            )}
          </button>
        </div>
      </div>

      {/* Expanded Details View */}
      {expanded && (
        <div
          onClick={(e) => e.stopPropagation()}
          className="mt-2 w-full overflow-hidden rounded-md border border-border/60 bg-muted/20"
        >
          <pre className="text-[11px] font-mono text-foreground p-3 whitespace-pre-wrap break-all max-h-64 overflow-y-auto leading-relaxed">
            {formattedJson}
          </pre>
        </div>
      )}
    </div>
  );
}

// ==================== Type Badges & Formatting ====================

type ValueType = "string" | "number" | "boolean" | "array" | "object" | "null" | "undefined";

function getValueType(val: unknown): ValueType {
  if (val === null) return "null";
  if (val === undefined) return "undefined";
  if (Array.isArray(val)) return "array";
  return typeof val as ValueType;
}

function TypeBadge({ type }: { type: ValueType }) {
  const styles: Record<ValueType, string> = {
    string: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20",
    number: "bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20",
    boolean: "bg-blue-500/10 text-blue-600 dark:text-blue-400 border-blue-500/20",
    array: "bg-purple-500/10 text-purple-600 dark:text-purple-400 border-purple-500/20",
    object: "bg-indigo-500/10 text-indigo-600 dark:text-indigo-400 border-indigo-500/20",
    null: "bg-muted text-muted-foreground border-border/50",
    undefined: "bg-muted text-muted-foreground border-border/50",
  };

  return (
    <span
      className={cn(
        "rounded-full px-1.5 py-0.5 text-[9px] font-mono font-medium border uppercase tracking-wider",
        styles[type],
      )}
    >
      {type}
    </span>
  );
}

function formatInlinePreview(val: unknown): string {
  if (val === null) return "null";
  if (val === undefined) return "undefined";
  if (typeof val === "string") return `"${val}"`;
  if (typeof val === "number" || typeof val === "boolean") return String(val);

  if (Array.isArray(val)) {
    if (val.length === 0) return "[] (empty array)";
    const sample = val.slice(0, 3).map((item) => formatInlinePreview(item)).join(", ");
    return `[ ${sample}${val.length > 3 ? ", …" : ""} ] (${val.length} items)`;
  }

  if (typeof val === "object") {
    const keys = Object.keys(val as Record<string, unknown>);
    if (keys.length === 0) return "{} (empty object)";
    const sample = keys
      .slice(0, 3)
      .map((k) => `${k}: ${formatInlinePreview((val as Record<string, unknown>)[k])}`)
      .join(", ");
    return `{ ${sample}${keys.length > 3 ? ", …" : ""} }`;
  }

  return String(val);
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className="flex flex-col items-center gap-2 py-6 text-center">
      <Database className="h-8 w-8 text-muted-foreground/30" />
      <p className="text-sm text-muted-foreground">{message}</p>
    </div>
  );
}
