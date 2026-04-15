import { useState, useCallback, useMemo, useRef, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Bot, ChevronDown, X, Loader2 } from "lucide-react";
import { useAgentDescriptors, groupAgentsByName, useAgentVersions } from "@/hooks/use-agents";
import { useDebounce } from "@/hooks/use-debounce";

interface AgentPickerProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  readOnly?: boolean;
}

export function AgentPicker({ value, onChange, placeholder, readOnly }: AgentPickerProps) {
  const { t } = useTranslation();
  
  // UI state
  const [popupOpen, setPopupOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const debouncedFilter = useDebounce(filter, 300);
  const [highlightedIndex, setHighlightedIndex] = useState(-1);

  // Refs
  const containerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  // For the selected chip
  const { data: versions, isLoading: loadingSelected } = useAgentVersions(value);
  const selectedName = versions?.[0]?.name;

  // For the dropdown list (backend filtering)
  const { data: rawAgents, isLoading: searchLoading } = useAgentDescriptors(50, 0, debouncedFilter);
  
  // Group by agent ID and keep latest version
  const agents = useMemo(() => {
    if (!rawAgents) return [];
    return groupAgentsByName(rawAgents);
  }, [rawAgents]);

  // Handlers
  const openPopup = useCallback(() => {
    if (readOnly) return;
    setFilter("");
    setHighlightedIndex(-1);
    setPopupOpen(true);
  }, [readOnly]);

  const closePopup = useCallback(() => {
    setPopupOpen(false);
    setFilter("");
    setHighlightedIndex(-1);
  }, []);

  const handleSelectKey = useCallback(
    (agentId: string) => {
      onChange(agentId);
      closePopup();
    },
    [onChange, closePopup]
  );

  const handleClear = useCallback(() => {
    if (readOnly) return;
    onChange("");
  }, [readOnly, onChange]);

  const handleInputKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (!popupOpen) {
        if (e.key === "ArrowDown") {
          e.preventDefault();
          openPopup();
        }
        return;
      }

      if (e.key === "Escape") {
        e.preventDefault();
        closePopup();
      } else if (e.key === "ArrowDown") {
        e.preventDefault();
        setHighlightedIndex((prev) =>
          prev < agents.length - 1 ? prev + 1 : 0
        );
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        setHighlightedIndex((prev) =>
          prev > 0 ? prev - 1 : agents.length - 1
        );
      } else if (e.key === "Enter") {
        e.preventDefault();
        if (highlightedIndex >= 0 && highlightedIndex < agents.length) {
          handleSelectKey(agents[highlightedIndex]!.id);
        } else if (filter) {
          // Allow entering custom agent IDs that don't exist yet
          onChange(filter);
          closePopup();
        }
      }
    },
    [popupOpen, openPopup, closePopup, agents, highlightedIndex, filter, onChange, handleSelectKey]
  );

  // Click outside to close
  useEffect(() => {
    if (!popupOpen) return;
    const handleMouseDown = (e: MouseEvent) => {
      if (
        containerRef.current &&
        !containerRef.current.contains(e.target as Node)
      ) {
        closePopup();
      }
    };
    document.addEventListener("mousedown", handleMouseDown);
    return () => document.removeEventListener("mousedown", handleMouseDown);
  }, [popupOpen, closePopup]);

  // Scroll active item into view
  useEffect(() => {
    if (highlightedIndex < 0 || !listRef.current) return;
    const items = listRef.current.querySelectorAll("[data-agent-item]");
    items[highlightedIndex]?.scrollIntoView({ block: "nearest" });
  }, [highlightedIndex]);

  // --- Render Selected Chip ---
  if (value) {
    return (
      <div ref={containerRef} className="relative flex-1">
        <div
          className={`flex h-9 items-center gap-2 rounded-md border px-3 ${
            readOnly
              ? "border-primary/20 bg-primary/5"
              : "border-primary/40 bg-primary/10"
          }`}
          title={selectedName ? `${value} — ${selectedName}` : value}
        >
          {loadingSelected ? (
            <Loader2 className="h-4 w-4 shrink-0 animate-spin text-primary" />
          ) : (
            <Bot className="h-4 w-4 shrink-0 text-primary" />
          )}
          <div className="flex flex-1 flex-col justify-center min-w-0">
            {selectedName && (
              <span className="truncate text-xs font-semibold text-foreground leading-tight pt-0.5">
                {selectedName}
              </span>
            )}
            <span className={`truncate font-mono font-medium ${selectedName ? 'text-[10px] text-muted-foreground leading-tight' : 'text-xs text-foreground'} pb-0.5`}>
              {value}
            </span>
          </div>
          {!readOnly && (
            <button
              type="button"
              onClick={handleClear}
              className="rounded p-0.5 text-muted-foreground opacity-70 transition-colors hover:bg-background/50 hover:text-foreground hover:opacity-100"
              aria-label={t("common.clear", "Clear selection")}
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      </div>
    );
  }

  // --- Render Search Input ---
  return (
    <div ref={containerRef} className="relative flex-1">
      <div className="flex w-full items-stretch h-9">
        <input
          ref={inputRef}
          type="text"
          value={filter}
          onChange={(e) => {
            setFilter(e.target.value);
            setHighlightedIndex(-1);
            if (!popupOpen) setPopupOpen(true);
          }}
          onFocus={openPopup}
          onKeyDown={handleInputKeyDown}
          placeholder={placeholder || t("agents.selectAgent", "Select Agent")}
          className="h-full flex-1 rounded-s-md border border-e-0 border-input bg-background ps-3 pe-2 text-xs text-foreground font-mono placeholder:font-sans focus:outline-none focus:ring-1 focus:ring-ring"
          autoComplete="off"
        />
        <button
          type="button"
          onClick={() => (popupOpen ? closePopup() : openPopup())}
          className={`flex h-full items-center justify-center border border-s-0 border-input px-2 text-xs transition-colors ${
            popupOpen
              ? "rounded-e-md bg-primary/10 text-primary"
              : "rounded-e-md bg-muted text-muted-foreground hover:bg-muted/80 hover:text-foreground"
          }`}
          tabIndex={-1}
        >
          <Bot className="h-3.5 w-3.5 mr-1" />
          <ChevronDown className={`h-3 w-3 transition-transform ${popupOpen ? "rotate-180" : ""}`} />
        </button>
      </div>

      {/* Popup Menu */}
      {popupOpen && (
        <div className="absolute inset-x-0 top-full z-50 mt-1 overflow-hidden rounded-lg border border-border bg-popover shadow-xl animate-in fade-in-0 zoom-in-95 slide-in-from-top-2 duration-150">
          <div ref={listRef} className="max-h-56 overflow-y-auto py-1">
            {searchLoading ? (
              <div className="flex items-center justify-center gap-2 py-4 text-xs text-muted-foreground">
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                {t("common.loading", "Loading...")}
              </div>
            ) : agents.length === 0 ? (
              <div className="px-3 py-3 text-center text-xs text-muted-foreground">
                {filter.trim() ? (
                  <>
                    <span className="block">{t("common.noResults", "No results found.")}</span>
                    <span className="mt-1 block text-[10px]">
                      Press Enter to use &quot;{filter}&quot; as ID.
                    </span>
                  </>
                ) : (
                  t("agents.empty", "No agents found")
                )}
              </div>
            ) : (
              agents.map((agent, idx) => (
                <button
                  key={agent.id}
                  type="button"
                  data-agent-item
                  onClick={() => handleSelectKey(agent.id)}
                  className={`flex w-full items-start gap-2 px-3 py-2 text-start text-xs transition-colors ${
                    idx === highlightedIndex
                      ? "bg-primary/10 text-foreground"
                      : "text-foreground hover:bg-secondary/50"
                  }`}
                >
                  <Bot className="mt-0.5 h-3.5 w-3.5 shrink-0 text-primary" />
                  <div className="min-w-0 flex-1">
                    <span className="block truncate font-medium">
                      {agent.name}
                    </span>
                    <span className="block truncate font-mono text-[10px] text-muted-foreground mt-0.5">
                      {agent.id}
                    </span>
                  </div>
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
}
