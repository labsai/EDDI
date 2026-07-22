import { useState, useMemo } from "react";
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
} from "lucide-react";

// ==================== Component ====================

interface MemoryInspectorProps {
  conversationId: string | null;
}

export function MemoryInspector({ conversationId }: MemoryInspectorProps) {
  const { t } = useTranslation();
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedTab, setSelectedTab] = useState<number | "props">(0);

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

  // Make sure selectedTab is valid; default to props if no steps but properties exist
  const currentTab =
    selectedTab === "props"
      ? (hasProperties ? "props" : 0)
      : (steps.length === 0 && hasProperties
        ? "props"
        : (selectedTab < steps.length ? selectedTab : 0));

  return (
    <div className="flex flex-col gap-3 p-3" data-testid="memory-inspector">
      {/* Header (Search + Refresh) */}
      <div className="flex items-center justify-between gap-2">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute start-2 top-1.5 h-4 w-4 text-muted-foreground" />
          <input
            type="text"
            placeholder={t("memoryInspector.search", "Search keys or values...")}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="h-7 w-full rounded-md border border-input bg-card ps-8 pe-3 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
        <button
          onClick={() => refetch()}
          className="flex h-7 w-7 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-muted hover:text-foreground"
          title={t("common.retry", "Refresh")}
          data-testid="memory-refresh"
        >
          <RefreshCw className="h-3.5 w-3.5" />
        </button>
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
                    ? "bg-primary text-primary-foreground"
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
                    ? "bg-primary text-primary-foreground"
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
                return <PropertiesTable properties={properties} searchQuery={searchQuery} />;
              }
              const activeStep = steps[currentTab as number];
              return activeStep ? <StepTable step={activeStep} searchQuery={searchQuery} /> : null;
            })()}
          </div>
        </>
      )}
    </div>
  );
}

// ==================== Step Table ====================

function StepTable({ step, searchQuery }: { step: DetailedConversationStep; searchQuery: string }) {
  const { t } = useTranslation();
  
  const items = useMemo(() => {
    let filtered = step.conversationStep ?? [];
    if (searchQuery) {
      const lowerQ = searchQuery.toLowerCase();
      filtered = filtered.filter(
        (item) =>
          item.key.toLowerCase().includes(lowerQ) ||
          String(item.value).toLowerCase().includes(lowerQ) ||
          (typeof item.value === "object" && JSON.stringify(item.value).toLowerCase().includes(lowerQ))
      );
    }
    return filtered;
  }, [step.conversationStep, searchQuery]);

  if (items.length === 0) {
    return (
      <div className="py-4 text-center text-xs text-muted-foreground">
        {t("memoryInspector.noMatches", "No matching data found")}
      </div>
    );
  }

  return (
    <div className="rounded-md border border-border bg-card">
      <div className="flex items-center justify-between border-b border-border bg-muted/30 px-3 py-1.5">
        <span className="text-xs font-semibold text-foreground/80">
          {t("memoryInspector.stepData", "Step Data")}
        </span>
        <span className="text-[10px] font-medium text-muted-foreground rounded-full bg-muted px-2 py-0.5">
          {items.length} {t("memoryInspector.keys", "keys")}
        </span>
      </div>
      <div className="divide-y divide-border">
        {items.map((item) => (
          <KeyValueRow key={item.key} itemKey={item.key} itemValue={item.value} />
        ))}
      </div>
    </div>
  );
}

// ==================== Properties Table ====================

function PropertiesTable({ properties, searchQuery }: { properties: Record<string, unknown>; searchQuery: string }) {
  const { t } = useTranslation();

  const { systemProps, customProps } = useMemo(() => {
    const sys: Record<string, unknown> = {};
    const custom: Record<string, unknown> = {};

    for (const [k, v] of Object.entries(properties)) {
      if (searchQuery) {
        const lowerQ = searchQuery.toLowerCase();
        const matches = k.toLowerCase().includes(lowerQ) || 
                        String(v).toLowerCase().includes(lowerQ) ||
                        (typeof v === "object" && JSON.stringify(v).toLowerCase().includes(lowerQ));
        if (!matches) continue;
      }

      // Grouping heuristic: start with underscore or specific known prefixes are system
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
      <div className="py-4 text-center text-xs text-muted-foreground">
        {t("memoryInspector.noMatches", "No matching data found")}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {hasCustom && (
        <PropertyGroup title={t("memoryInspector.customProperties", "Custom Properties")} props={customProps} />
      )}
      {hasSystem && (
        <PropertyGroup title={t("memoryInspector.systemProperties", "System Properties")} props={systemProps} />
      )}
    </div>
  );
}

function PropertyGroup({ title, props }: { title: string; props: Record<string, unknown> }) {
  const { t } = useTranslation();
  const entries = Object.entries(props);
  return (
    <div className="rounded-md border border-border bg-card">
      <div className="flex items-center justify-between border-b border-border bg-muted/30 px-3 py-1.5">
        <span className="text-xs font-semibold text-foreground/80">{title}</span>
        <span className="text-[10px] font-medium text-muted-foreground rounded-full bg-muted px-2 py-0.5">
          {entries.length} {t("memoryInspector.keys", "keys")}
        </span>
      </div>
      <div className="divide-y divide-border">
        {entries.map(([k, v]) => (
          <KeyValueRow key={k} itemKey={k} itemValue={v} />
        ))}
      </div>
    </div>
  );
}

// ==================== Shared Rows ====================

function KeyValueRow({ itemKey, itemValue }: { itemKey: string; itemValue: unknown }) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);

  const isComplex = typeof itemValue === "object" && itemValue !== null;
  const displayValue = isComplex ? JSON.stringify(itemValue, null, 2) : String(itemValue);
  const isLong = displayValue.length > 80 || displayValue.includes("\n");

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(displayValue);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Ignored
    }
  };

  return (
    <div className="group flex flex-col p-2 hover:bg-muted/20">
      <div className="flex items-start gap-3">
        <div className="w-1/3 min-w-0 shrink-0">
          <p className="truncate text-xs font-medium text-muted-foreground" title={itemKey}>
            {itemKey}
          </p>
        </div>
        <div className="flex-1 min-w-0">
          {!expanded ? (
            <p className="truncate text-xs font-mono text-foreground/80" title={!isComplex ? displayValue : t("memoryInspector.object", "Object")}>
              {isComplex ? "{...}" : displayValue}
            </p>
          ) : (
            <div className="w-full overflow-hidden">
              <pre className="text-[10px] font-mono text-foreground/80 whitespace-pre-wrap break-all max-h-48 overflow-y-auto rounded bg-muted/30 p-2 border border-border/50">
                {displayValue}
              </pre>
            </div>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          {isLong && (
            <button
              onClick={() => setExpanded(!expanded)}
              className="flex h-5 w-5 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
              title={expanded ? t("memoryInspector.collapse", "Collapse") : t("memoryInspector.expand", "Expand")}
              aria-expanded={expanded}
              aria-label={expanded ? t("memoryInspector.collapse", "Collapse") : t("memoryInspector.expand", "Expand")}
            >
              <ChevronDown className={cn("h-3 w-3 transition-transform", expanded && "rotate-180")} />
            </button>
          )}
          <button
            onClick={handleCopy}
            className="flex h-5 w-5 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
            title={t("memoryInspector.copyValue", "Copy value")}
            aria-label={t("memoryInspector.copyValue", "Copy value")}
          >
            {copied ? <Check className="h-3 w-3 text-emerald-500" /> : <Copy className="h-3 w-3" />}
          </button>
        </div>
      </div>
    </div>
  );
}

function EmptyState({ message }: { message: string }) {
  return (
    <div className="flex flex-col items-center gap-2 py-6 text-center">
      <Database className="h-8 w-8 text-muted-foreground/30" />
      <p className="text-sm text-muted-foreground">{message}</p>
    </div>
  );
}

