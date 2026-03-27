import { useTranslation } from "react-i18next";
import { useDebugStore, type DebugTab } from "@/hooks/use-debug-events";
import { PipelineTrace } from "./pipeline-trace";
import { CostDashboard } from "./cost-dashboard";
import { MemoryInspector } from "./memory-inspector";
import { LiveLogViewer } from "./live-log-viewer";
import { PromptViewer } from "./prompt-viewer";
import { cn } from "@/lib/utils";
import {
  GitBranch,
  Coins,
  Database,
  ScrollText,
  MessageSquareCode,
  ChevronUp,
  ChevronDown,
} from "lucide-react";

// ==================== Tab Configuration ====================

interface TabConfig {
  id: DebugTab;
  labelKey: string;
  fallback: string;
  icon: React.ReactNode;
}

const TABS: TabConfig[] = [
  { id: "pipeline", labelKey: "debugDrawer.tabPipeline", fallback: "Pipeline", icon: <GitBranch className="h-3.5 w-3.5" /> },
  { id: "costs", labelKey: "debugDrawer.tabCosts", fallback: "Costs", icon: <Coins className="h-3.5 w-3.5" /> },
  { id: "memory", labelKey: "debugDrawer.tabMemory", fallback: "Memory", icon: <Database className="h-3.5 w-3.5" /> },
  { id: "logs", labelKey: "debugDrawer.tabLogs", fallback: "Logs", icon: <ScrollText className="h-3.5 w-3.5" /> },
  { id: "prompt", labelKey: "debugDrawer.tabPrompt", fallback: "Prompt", icon: <MessageSquareCode className="h-3.5 w-3.5" /> },
];

// ==================== Component ====================

interface DebugDrawerProps {
  conversationId: string | null;
  agentId: string | null;
}

export function DebugDrawer({ conversationId, agentId }: DebugDrawerProps) {
  const { t } = useTranslation();
  const isOpen = useDebugStore((s) => s.isDebugOpen);
  const activeTab = useDebugStore((s) => s.activeTab);
  const setActiveTab = useDebugStore((s) => s.setActiveTab);
  const toggleDebug = useDebugStore((s) => s.toggleDebug);

  return (
    <div data-testid="debug-drawer">
      {/* Toggle bar */}
      <button
        onClick={toggleDebug}
        aria-expanded={isOpen}
        aria-controls="debug-drawer-content"
        className={cn(
          "flex w-full items-center justify-center gap-1.5 border-t border-border px-4 py-1.5 text-xs font-medium transition-colors",
          isOpen
            ? "bg-primary/5 text-primary hover:bg-primary/10"
            : "text-muted-foreground hover:bg-muted hover:text-foreground",
        )}
        data-testid="debug-toggle"
      >
        {isOpen ? (
          <ChevronDown className="h-3.5 w-3.5" />
        ) : (
          <ChevronUp className="h-3.5 w-3.5" />
        )}
        {t("debugDrawer.title", "Debug")}
      </button>

      {/* Drawer content */}
      {isOpen && (
        <div id="debug-drawer-content" className="flex flex-col border-t border-border bg-background">
          {/* Tab strip */}
          <div className="flex border-b border-border overflow-x-auto" role="tablist" aria-label={t("debugDrawer.title", "Debug")}>
            {TABS.map((tab) => (
              <button
                key={tab.id}
                role="tab"
                aria-selected={activeTab === tab.id}
                aria-controls={`debug-tabpanel-${tab.id}`}
                id={`debug-tab-${tab.id}`}
                onClick={() => setActiveTab(tab.id)}
                className={cn(
                  "flex items-center gap-1 whitespace-nowrap px-3 py-2 text-xs font-medium transition-colors",
                  activeTab === tab.id
                    ? "border-b-2 border-primary text-primary"
                    : "text-muted-foreground hover:text-foreground",
                )}
                data-testid={`debug-tab-${tab.id}`}
              >
                {tab.icon}
                <span className="hidden sm:inline">{t(tab.labelKey, tab.fallback)}</span>
              </button>
            ))}
          </div>

          {/* Tab content */}
          <div
            role="tabpanel"
            id={`debug-tabpanel-${activeTab}`}
            aria-labelledby={`debug-tab-${activeTab}`}
            className="overflow-y-auto"
            style={{ maxHeight: "40vh" }}
          >
            {activeTab === "pipeline" && (
              <PipelineTrace conversationId={conversationId} />
            )}
            {activeTab === "costs" && (
              <CostDashboard conversationId={conversationId} isActive />
            )}
            {activeTab === "memory" && (
              <MemoryInspector conversationId={conversationId} />
            )}
            {activeTab === "logs" && (
              <LiveLogViewer agentId={agentId} conversationId={conversationId} />
            )}
            {activeTab === "prompt" && (
              <PromptViewer conversationId={conversationId} />
            )}
          </div>
        </div>
      )}
    </div>
  );
}
