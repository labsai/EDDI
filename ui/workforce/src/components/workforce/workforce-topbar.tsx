import { AppLink } from "@eddi/app-kit/components/shared/app-link";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ChevronLeft, Menu, Sparkles } from "lucide-react";
import { Button } from "@eddi/ui";
import { useWorkforceAccess } from "@eddi/app-kit/hooks/use-workforce-access";
import { cn } from "@eddi/app-kit/lib/utils";
import { ApprovalsIndicator } from "@/components/workforce/approvals-indicator";

// ─── Types ───────────────────────────────────────────────────────

export interface WorkforceTopbarProps {
  onMenuClick?: () => void;
  title?: string;
  backTo?: string;
  rightContent?: React.ReactNode;
}

// ─── Component ───────────────────────────────────────────────────

export function WorkforceTopbar({
  onMenuClick,
  title,
  backTo,
  rightContent,
}: WorkforceTopbarProps) {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { isAdmin } = useWorkforceAccess();

  return (
    <header
      className={cn(
        // The notch / status bar of an installed app sits above the bar, not on it.
        "sticky top-0 z-30 flex h-[calc(3.5rem+env(safe-area-inset-top))] items-center gap-2 ps-4 pe-4 pt-[env(safe-area-inset-top)]",
        "border-b border-border bg-card/80 backdrop-blur-sm",
      )}
    >
      {/* Left side: back arrow or hamburger */}
      {backTo && (
        <Button
          variant="ghost"
          size="icon"
          className="h-9 w-9 shrink-0"
          onClick={() => navigate(backTo)}
          aria-label={t("Workforce.back", "Back")}
        >
          <ChevronLeft className="h-5 w-5 rtl:rotate-180" />
        </Button>
      )}

      {onMenuClick && !backTo && (
        <Button
          variant="ghost"
          size="icon"
          className="h-9 w-9 shrink-0"
          onClick={onMenuClick}
          aria-label={t("Workforce.openMenu", "Open menu")}
        >
          <Menu className="h-5 w-5" />
        </Button>
      )}

      {/* Title — only shown when a specific page title is set */}
      {title && (
        <div className="min-w-0 flex-1 truncate text-lg font-semibold text-foreground">
          {title}
        </div>
      )}

      {/* Spacer when no title */}
      {!title && <div className="flex-1" />}

      {/* Right side: the page's own actions, then — for administrators only —
          a link to the Platform Operator. Workforce does not mount the
          operator's drawer: the operator, its allow-list and its approval
          guards are a Manager feature (decision D5), and an end user has no
          business with an agent that operates the deployment. */}
      <div className="flex shrink-0 items-center gap-1">
        {rightContent}
        {/* What waits on a human (T2.3), on every viewport. */}
        <ApprovalsIndicator />
        {isAdmin && (
          <Button asChild variant="ghost" size="sm" className="gap-1.5">
            <AppLink to="/manage/operator" data-testid="workforce-operator-link">
              <Sparkles className="h-4 w-4" />
              <span className="hidden sm:inline">
                {t("Workforce.operatorLink", "Platform Operator")}
              </span>
            </AppLink>
          </Button>
        )}
      </div>
    </header>
  );
}
