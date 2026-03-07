import type React from "react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import {
  Plus,
  X,
  RefreshCw,
  FileText,
  GitBranch,
  Globe,
  Brain,
  MessageSquareText,
  Settings,
  FileCode,
  Puzzle,
} from "lucide-react";
import { useExtensionTypes } from "@/hooks/use-extensions-store";
import { EXTENSION_TYPE_INFO } from "@/lib/api/extensions";
import type { ExtensionDescriptor } from "@/lib/api/extensions";

/* ─── Icon map ─── */
const iconMap: Record<string, React.ComponentType<{ className?: string }>> = {
  FileText,
  GitBranch,
  Globe,
  Brain,
  MessageSquareText,
  Settings,
  FileCode,
};

function getIcon(type: string): React.ComponentType<{ className?: string }> {
  const info = EXTENSION_TYPE_INFO[type];
  if (info && iconMap[info.icon]) return iconMap[info.icon]!;
  return Puzzle;
}

export interface AddExtensionDialogProps {
  open: boolean;
  onClose: () => void;
  onSelect: (descriptor: ExtensionDescriptor) => void;
}

/**
 * Dialog listing available extension types from /extensionstore/extensions.
 * On selection, calls onSelect with the chosen ExtensionDescriptor.
 */
export function AddExtensionDialog({
  open,
  onClose,
  onSelect,
}: AddExtensionDialogProps) {
  const { t } = useTranslation();
  const [filter, setFilter] = useState("");
  const { data: extensionTypes, isLoading } = useExtensionTypes();

  if (!open) return null;

  // Sort by pipeline order
  const sorted = [...(extensionTypes ?? [])].sort((a, b) => {
    const orderA = EXTENSION_TYPE_INFO[a.type]?.order ?? 99;
    const orderB = EXTENSION_TYPE_INFO[b.type]?.order ?? 99;
    return orderA - orderB;
  });

  const filtered = filter
    ? sorted.filter(
        (ext) =>
          ext.displayName.toLowerCase().includes(filter.toLowerCase()) ||
          ext.type.toLowerCase().includes(filter.toLowerCase())
      )
    : sorted;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" data-testid="add-extension-dialog">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/50 backdrop-blur-sm"
        onClick={onClose}
      />

      {/* Dialog */}
      <div className="relative z-10 w-full max-w-md rounded-xl border bg-card shadow-xl mx-4">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border p-5">
          <div className="flex items-center gap-2">
            <Plus className="h-5 w-5 text-primary" />
            <h3 className="text-lg font-semibold text-foreground">
              {t("packageEditor.addExtension", "Add Extension")}
            </h3>
          </div>
          <button
            onClick={onClose}
            className="rounded-md p-1 text-muted-foreground hover:bg-secondary transition-colors"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Search */}
        <div className="p-4 pb-0">
          <input
            type="text"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={t("common.search")}
            className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="extension-search"
            autoFocus
          />
        </div>

        {/* Extension list */}
        <div className="max-h-80 divide-y divide-border overflow-y-auto p-2">
          {isLoading && (
            <div className="flex items-center justify-center py-8">
              <RefreshCw className="h-5 w-5 animate-spin text-primary" />
            </div>
          )}

          {!isLoading && filtered.length === 0 && (
            <p className="py-8 text-center text-sm text-muted-foreground">
              {t("common.noResults")}
            </p>
          )}

          {filtered.map((ext) => {
            const Icon = getIcon(ext.type);
            const info = EXTENSION_TYPE_INFO[ext.type];
            return (
              <button
                key={ext.type}
                onClick={() => onSelect(ext)}
                className="flex w-full items-center gap-3 rounded-lg px-3 py-3 text-start hover:bg-secondary/70 transition-colors"
                data-testid={`ext-option-${ext.type}`}
              >
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary/10">
                  <Icon className="h-5 w-5 text-primary" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-foreground">
                    {ext.displayName || info?.label || ext.type}
                  </p>
                  <p className="text-xs text-muted-foreground truncate">
                    {ext.type}
                  </p>
                </div>
                <Plus className="h-4 w-4 shrink-0 text-primary" />
              </button>
            );
          })}
        </div>

        {/* Footer hint */}
        <div className="border-t border-border p-3">
          <p className="text-xs text-center text-muted-foreground">
            {t(
              "packageEditor.addHintDialog",
              "Select an extension type to add to the pipeline"
            )}
          </p>
        </div>
      </div>
    </div>
  );
}
