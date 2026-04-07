import { useCallback } from "react";
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

  /** Full ARIA keyboard navigation: Arrow Left/Right, Home/End */
  const handleTabKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLButtonElement>) => {
      const currentIndex = TABS.findIndex((tab) => tab.id === activeTab);
      let nextIndex: number | null = null;

      switch (e.key) {
        case "ArrowRight":
        case "ArrowDown":
          nextIndex = (currentIndex + 1) % TABS.length;
          break;
        case "ArrowLeft":
        case "ArrowUp":
          nextIndex = (currentIndex - 1 + TABS.length) % TABS.length;
          break;
        case "Home":
          nextIndex = 0;
          break;
        case "End":
          nextIndex = TABS.length - 1;
          break;
        default:
          return; // Don't prevent default for other keys
      }

      e.preventDefault();
      const nextTab = TABS[nextIndex]!;
      setActiveTab(nextTab.id);
      // Focus the newly active tab button
      const nextEl = document.getElementById(`debug-tab-${nextTab.id}`);
      nextEl?.focus();
    },
    [activeTab, setActiveTab],
  );

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
                tabIndex={activeTab === tab.id ? 0 : -1}
                onClick={() => setActiveTab(tab.id)}
                onKeyDown={handleTabKeyDown}
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

