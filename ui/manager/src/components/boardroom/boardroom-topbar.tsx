import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ChevronLeft, Menu } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

// ─── Types ───────────────────────────────────────────────────────

export interface BoardroomTopbarProps {
  onMenuClick?: () => void;
  title?: string;
  backTo?: string;
  rightContent?: React.ReactNode;
}

// ─── Component ───────────────────────────────────────────────────

export function BoardroomTopbar({
  onMenuClick,
  title,
  backTo,
  rightContent,
}: BoardroomTopbarProps) {
  const navigate = useNavigate();
  const { t } = useTranslation();

  return (
    <header
      className={cn(
        "sticky top-0 z-30 flex h-14 items-center gap-2 px-4",
        "border-b border-slate-200 bg-white/80 backdrop-blur-sm",
        "dark:border-slate-800 dark:bg-slate-900/80",
      )}
    >
      {/* Left side: back arrow or hamburger */}
      {backTo && (
        <Button
          variant="ghost"
          size="icon"
          className="h-9 w-9 shrink-0"
          onClick={() => navigate(backTo)}
          aria-label={t("boardroom.back", "Back")}
        >
          <ChevronLeft className="h-5 w-5" />
        </Button>
      )}

      {onMenuClick && !backTo && (
        <Button
          variant="ghost"
          size="icon"
          className="h-9 w-9 shrink-0"
          onClick={onMenuClick}
          aria-label={t("boardroom.openMenu", "Open menu")}
        >
          <Menu className="h-5 w-5" />
        </Button>
      )}

      {/* Title */}
      <h1 className="min-w-0 flex-1 truncate text-lg font-semibold text-slate-900 dark:text-slate-50">
        {title || (
          <span className="flex items-center gap-1.5">
            <span className="text-indigo-500 dark:text-indigo-400">✦</span>
            {t("boardroom.title", "Boardroom")}
          </span>
        )}
      </h1>

      {/* Right side: action slot */}
      {rightContent && (
        <div className="flex shrink-0 items-center gap-1">
          {rightContent}
        </div>
      )}
    </header>
  );
}
