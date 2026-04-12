import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearchParams } from "react-router-dom";
import { Users, Brain, Database, Link2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { UserMemoryPage } from "./user-memory";
import { PropertiesPage } from "./properties";
import { UserConversationsPage } from "./user-conversations";

const TABS = ["memories", "properties", "conversations"] as const;
type Tab = (typeof TABS)[number];

const TAB_ICONS: Record<Tab, React.ElementType> = {
  memories: Brain,
  properties: Database,
  conversations: Link2,
};

const TAB_COLORS: Record<Tab, string> = {
  memories: "text-teal-500",
  properties: "text-indigo-500",
  conversations: "text-blue-500",
};

export function UserDataPage() {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialTab = (searchParams.get("tab") as Tab) || "memories";
  const [activeTab, setActiveTab] = useState<Tab>(
    TABS.includes(initialTab) ? initialTab : "memories",
  );

  const handleTabChange = (tab: Tab) => {
    setActiveTab(tab);
    setSearchParams({ tab }, { replace: true });
  };

  const tabLabels: Record<Tab, string> = {
    memories: t("memories.title", "Memories"),
    properties: t("properties.title", "Properties"),
    conversations: t("userConversations.title", "Conversations"),
  };

  return (
    <div className="space-y-6" data-testid="user-data-page">
      {/* Header */}
      <div>
        <h1 className="flex items-center gap-3 text-2xl font-bold text-foreground">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-violet-500/10">
            <Users className="h-5 w-5 text-violet-500" />
          </div>
          {t("userData.title", "User Data")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t(
            "userData.subtitle",
            "Manage per-user memories, properties, and conversation bindings",
          )}
        </p>
      </div>

      {/* Tab bar */}
      <div
        className="flex gap-1 rounded-xl border border-border bg-card p-1"
        role="tablist"
        aria-label={t("userData.tabs", "User data sections")}
      >
        {TABS.map((tab) => {
          const Icon = TAB_ICONS[tab];
          const isActive = activeTab === tab;
          return (
            <button
              key={tab}
              role="tab"
              aria-selected={isActive}
              aria-controls={`panel-${tab}`}
              onClick={() => handleTabChange(tab)}
              className={cn(
                "flex flex-1 items-center justify-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium transition-all",
                isActive
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground hover:bg-background/50",
              )}
              data-testid={`tab-${tab}`}
            >
              <Icon
                className={cn("h-4 w-4", isActive ? TAB_COLORS[tab] : "")}
              />
              <span className="hidden sm:inline">{tabLabels[tab]}</span>
            </button>
          );
        })}
      </div>

      {/* Tab panels */}
      <div
        role="tabpanel"
        id={`panel-${activeTab}`}
        aria-labelledby={`tab-${activeTab}`}
      >
        {activeTab === "memories" && <UserMemoryPage embedded />}
        {activeTab === "properties" && <PropertiesPage embedded />}
        {activeTab === "conversations" && <UserConversationsPage embedded />}
      </div>
    </div>
  );
}
