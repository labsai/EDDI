import { useTranslation } from "react-i18next";
import {
  Package,
  Copy,
  Trash2,
  MoreVertical,
  ExternalLink,
} from "lucide-react";
import { cn } from "@/lib/utils";
import type { BotDescriptor } from "@/lib/api/bots";
import { useState } from "react";
import { Link } from "react-router-dom";

interface PackageCardProps {
  pkg: BotDescriptor & { id: string; version: number };
  onDuplicate: (id: string, version: number) => void;
  onDelete: (id: string, version: number) => void;
}

export function PackageCard({ pkg, onDuplicate, onDelete }: PackageCardProps) {
  const { t } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);

  const timeAgo = formatTimeAgo(pkg.lastModifiedOn);

  return (
    <div
      className={cn(
        "group relative flex flex-col rounded-xl border bg-card p-5 shadow-sm transition-all duration-200",
        "hover:shadow-md hover:border-primary/30"
      )}
      data-testid={`package-card-${pkg.id}`}
    >
      {/* Icon + menu */}
      <div className="flex items-start justify-between">
        <div className="rounded-lg bg-primary/10 p-2">
          <Package className="h-5 w-5 text-primary" />
        </div>

        {/* Context menu */}
        <div className="relative">
          <button
            onClick={() => setMenuOpen(!menuOpen)}
            className="rounded-md p-1 text-muted-foreground opacity-0 transition-opacity hover:bg-secondary hover:text-foreground group-hover:opacity-100"
            data-testid={`package-menu-${pkg.id}`}
          >
            <MoreVertical className="h-4 w-4" />
          </button>
          {menuOpen && (
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setMenuOpen(false)}
              />
              <div className="absolute end-0 z-50 mt-1 w-44 rounded-lg border bg-popover py-1 shadow-lg">
                <button
                  onClick={() => {
                    onDuplicate(pkg.id, pkg.version);
                    setMenuOpen(false);
                  }}
                  className="flex w-full items-center gap-2 px-3 py-2 text-sm text-popover-foreground hover:bg-secondary"
                >
                  <Copy className="h-4 w-4" />
                  {t("common.duplicate", "Duplicate")}
                </button>
                <button
                  onClick={() => {
                    onDelete(pkg.id, pkg.version);
                    setMenuOpen(false);
                  }}
                  className="flex w-full items-center gap-2 px-3 py-2 text-sm text-destructive hover:bg-destructive/10"
                >
                  <Trash2 className="h-4 w-4" />
                  {t("common.delete")}
                </button>
              </div>
            </>
          )}
        </div>
      </div>

      {/* Package info */}
      <div className="mt-4 flex-1">
        <Link
          to={`/manage/packageview/${pkg.id}`}
          className="text-lg font-semibold text-foreground hover:text-primary transition-colors"
        >
          {pkg.name || t("packages.unnamed", "Unnamed Package")}
          <ExternalLink className="ms-1 inline h-3.5 w-3.5 opacity-0 group-hover:opacity-50" />
        </Link>
        <p className="mt-0.5 font-mono text-xs text-muted-foreground/70 truncate" title={pkg.id}>
          {pkg.id}
        </p>
        <p className="mt-1 line-clamp-2 text-sm text-muted-foreground">
          {pkg.description || t("packages.noDescription", "No description")}
        </p>
      </div>

      {/* Footer */}
      <div className="mt-4 flex items-center justify-between border-t border-border pt-3">
        <span
          className="text-xs text-muted-foreground"
          title={new Date(pkg.lastModifiedOn).toLocaleString()}
        >
          {timeAgo}
        </span>
        <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
          v{pkg.version}
        </span>
      </div>
    </div>
  );
}

function formatTimeAgo(timestamp: number): string {
  const now = Date.now();
  const diff = now - timestamp;
  const minutes = Math.floor(diff / 60000);
  const hours = Math.floor(diff / 3600000);
  const days = Math.floor(diff / 86400000);

  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes}m ago`;
  if (hours < 24) return `${hours}h ago`;
  if (days < 30) return `${days}d ago`;
  return new Date(timestamp).toLocaleDateString();
}
