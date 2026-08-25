import type { MouseEvent } from "react";
import { Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";

interface BackLinkProps {
  to: string;
  label: string;
  /**
   * Take over the in-app navigation, e.g. to ask about unsaved edits first.
   *
   * Only plain left clicks are intercepted. A modified click (new tab, new
   * window) and a middle click are left to the browser: they do not navigate
   * *this* page, so there is nothing to guard, and swallowing them would cost
   * the affordance the anchor exists for.
   */
  onNavigate?: (to: string) => void;
}

export function BackLink({ to, label, onNavigate }: BackLinkProps) {
  const handleClick = (e: MouseEvent<HTMLAnchorElement>) => {
    if (!onNavigate) return;
    if (e.defaultPrevented) return;
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
    e.preventDefault();
    onNavigate(to);
  };

  return (
    <Link
      to={to}
      onClick={handleClick}
      className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
      data-testid="back-to-list"
    >
      <ArrowLeft className="h-4 w-4" />
      {label}
    </Link>
  );
}
