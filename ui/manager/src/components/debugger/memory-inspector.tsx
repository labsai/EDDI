import { useState, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import { cn } from "@/lib/utils";
import {
  getDetailedConversation,
  type DetailedConversationStep,
  type DetailedConversationStepItem,
} from "@/lib/api/conversations";
import {
  ChevronRight,
  Database,
  Eye,
  EyeOff,
  RefreshCw,
} from "lucide-react";

// ==================== Component ====================

interface MemoryInspectorProps {
  conversationId: string | null;
}

export function MemoryInspector({ conversationId }: MemoryInspectorProps) {
  const { t } = useTranslation();

  const { data, isLoading, refetch } = useQuery({
    queryKey: ["memory", "detailed", conversationId],
    queryFn: () => getDetailedConversation(conversationId!),
    enabled: !!conversationId,
    staleTime: 10_000,
  });

  if (!conversationId) {
    return (
      <EmptyState
        message={t("memoryInspector.noConversation", "Start a conversation to inspect memory")}
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

  const steps = data?.conversationSteps ?? [];

  return (
    <div className="flex flex-col gap-2 p-3" data-testid="memory-inspector">
      {/* Header */}
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-foreground/80">
          {t("memoryInspector.title", "Conversation Memory")}
        </span>
        <button
          onClick={() => refetch()}
          className="flex h-6 w-6 items-center justify-center rounded text-muted-foreground hover:bg-muted hover:text-foreground"
          title={t("common.retry", "Refresh")}
          data-testid="memory-refresh"
        >
          <RefreshCw className="h-3 w-3" />
        </button>
      </div>

      {steps.length === 0 ? (
        <EmptyState
          message={t("memoryInspector.empty", "No memory data available")}
        />
      ) : (
        steps.map((step, stepIdx) => (
          <StepNode key={stepIdx} step={step} stepIndex={stepIdx} />
        ))
      )}

      {/* Properties */}
      {data?.conversationProperties && Object.keys(data.conversationProperties).length > 0 && (
        <ExpandableNode label={t("memoryInspector.properties", "Properties")} defaultOpen={false}>
          <pre className="text-[10px] font-mono text-muted-foreground whitespace-pre-wrap break-all">
            {JSON.stringify(data.conversationProperties, null, 2)}
          </pre>
        </ExpandableNode>
      )}
    </div>
  );
}

// ==================== Step Node ====================

function StepNode({ step, stepIndex }: { step: DetailedConversationStep; stepIndex: number }) {
  const { t } = useTranslation();

  // Group items by originWorkflowId
  const groups = useMemo(() => {
    const map = new Map<string, DetailedConversationStepItem[]>();
    for (const item of step.conversationStep ?? []) {
      const group = item.originWorkflowId ?? "input";
      if (!map.has(group)) map.set(group, []);
      map.get(group)!.push(item);
    }
    return Array.from(map.entries());
  }, [step.conversationStep]);

  return (
    <ExpandableNode
      label={`${t("memoryInspector.step", "Step")} ${stepIndex + 1}`}
      badge={`${(step.conversationStep ?? []).length} ${t("memoryInspector.keys", "keys")}`}
      defaultOpen={stepIndex === 0}
    >
      {groups.map(([groupId, items]) => (
        <div key={groupId} className="ms-2 border-s border-border ps-2 space-y-0.5">
          <span className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wide">
            {groupId === "input" ? t("memoryInspector.input", "Input") : groupId}
          </span>
          {items.map((item, idx) => (
            <DataKeyNode key={idx} item={item} />
          ))}
        </div>
      ))}
    </ExpandableNode>
  );
}

// ==================== Data Key Node ====================

function DataKeyNode({ item }: { item: DetailedConversationStepItem }) {
  const [expanded, setExpanded] = useState(false);
  const isComplex = typeof item.value === "object" && item.value !== null;
  const isPublic = !item.key.includes("private");
  const displayValue = isComplex
    ? JSON.stringify(item.value, null, 2)
    : String(item.value);
  const isLong = displayValue.length > 80;

  return (
    <div className="rounded-sm">
      <button
        onClick={() => isLong && setExpanded(!expanded)}
        className={cn(
          "flex w-full items-center gap-1 py-0.5 text-start text-[10px]",
          isLong && "cursor-pointer hover:bg-muted/30 rounded px-1 -mx-1",
        )}
      >
        {isPublic ? (
          <Eye className="h-2.5 w-2.5 shrink-0 text-emerald-500/60" />
        ) : (
          <EyeOff className="h-2.5 w-2.5 shrink-0 text-amber-500/60" />
        )}
        <span className="font-mono font-medium text-primary/80 shrink-0">{item.key}</span>
        <span className="text-muted-foreground/60 mx-0.5">=</span>
        {!expanded && (
          <span className="truncate font-mono text-foreground/70">
            {displayValue.slice(0, 80)}
            {isLong && "…"}
          </span>
        )}
      </button>
      {expanded && (
        <pre className="ms-5 mt-0.5 rounded border border-border bg-card p-1.5 text-[10px] font-mono text-foreground/70 whitespace-pre-wrap break-all max-h-48 overflow-y-auto">
          {displayValue}
        </pre>
      )}
    </div>
  );
}

// ==================== Shared UI ====================

function ExpandableNode({
  label,
  badge,
  defaultOpen = false,
  children,
}: {
  label: string;
  badge?: string;
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);

  return (
    <div className="rounded-md border border-border bg-card/30">
      <button
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="flex w-full items-center gap-1.5 px-2 py-1.5 text-xs font-medium text-foreground hover:bg-muted/30 rounded-t-md"
        data-testid="expandable-node"
      >
        <ChevronRight
          className={cn(
            "h-3 w-3 shrink-0 text-muted-foreground transition-transform",
            open && "rotate-90",
          )}
        />
        {label}
        {badge && (
          <span className="ms-auto text-[10px] font-normal text-muted-foreground">
            {badge}
          </span>
        )}
      </button>
      {open && <div className="px-2 pb-2 space-y-1">{children}</div>}
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
