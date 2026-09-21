import { useTranslation } from "react-i18next";
import { Check, ChevronDown, Users, User, Layers } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { ALL_SPACES, useSpaces } from "@/hooks/use-spaces";

/**
 * Chooses which workspace the listings are filtered to.
 *
 * <h3>Why it hides itself</h3> A user with one space would be offered a control
 * whose only option is the view they already have. Workspaces are also off by
 * default, and on a deployment without them everyone has exactly one space — so
 * the switcher simply does not render rather than teaching a concept the
 * deployment does not use.
 *
 * The choice is a *narrowing*. The backend scopes every listing to what the
 * caller may see regardless, and asking for a space you cannot reach returns
 * nothing rather than granting it, so nothing here is load-bearing for access.
 */
export function SpaceSwitcher({ className }: { className?: string }) {
  const { t } = useTranslation();
  const { spaces, activeSpace, setActiveSpace, active, hasChoice } = useSpaces();

  if (!hasChoice) return null;

  // The menu calls the personal space "My workspace"; the trigger used
  // `active.label` unconditionally and so showed the raw principal after
  // selecting it — the same space named two different things one click apart.
  const label = active
    ? active.kind === "personal"
      ? t("workspaces.personalSpace", "My workspace")
      : active.label
    : t("workspaces.allSpaces", "All workspaces");
  const ActiveIcon = active ? (active.kind === "team" ? Users : User) : Layers;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn(
          "flex items-center gap-2 rounded-md border border-border bg-background px-2.5 py-1.5",
          "text-sm text-foreground transition-colors hover:bg-accent",
          "focus:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          className
        )}
        data-testid="space-switcher"
        // The label INCLUDES the active space. An `aria-label` replaces the
        // element's text as its accessible name, so labelling this "Switch
        // workspace" alone meant a screen-reader user heard the same thing
        // whichever workspace was selected — the one fact the control exists to
        // convey was the one fact it did not convey.
        aria-label={t("workspaces.switcherCurrent", "Switch workspace — currently {{space}}", { space: label })}
      >
        <ActiveIcon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="max-w-[10rem] truncate">{label}</span>
        <ChevronDown className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" className="min-w-56">
        <DropdownMenuLabel>{t("workspaces.switcherLabel", "Switch workspace")}</DropdownMenuLabel>

        <DropdownMenuItem
          onClick={() => setActiveSpace(ALL_SPACES)}
          data-testid="space-option-all"
          aria-current={activeSpace === ALL_SPACES ? "true" : undefined}
        >
          <Layers className="me-2 h-4 w-4 text-muted-foreground" aria-hidden="true" />
          <span className="flex-1">{t("workspaces.allSpaces", "All workspaces")}</span>
          {activeSpace === ALL_SPACES && <Check className="ms-2 h-4 w-4" aria-hidden="true" />}
        </DropdownMenuItem>

        <DropdownMenuSeparator />

        {spaces.map((space) => {
          const Icon = space.kind === "team" ? Users : User;
          return (
            <DropdownMenuItem
              key={space.id}
              onClick={() => setActiveSpace(space.id)}
              data-testid={`space-option-${space.id}`}
              // The tick beside the active entry is aria-hidden, so without this
              // the selection is invisible to assistive tech in the menu too.
              aria-current={activeSpace === space.id ? "true" : undefined}
            >
              <Icon className="me-2 h-4 w-4 text-muted-foreground" aria-hidden="true" />
              <span className="flex-1 truncate">
                {space.kind === "personal"
                  ? t("workspaces.personalSpace", "My workspace")
                  : space.label}
              </span>
              {activeSpace === space.id && <Check className="ms-2 h-4 w-4" aria-hidden="true" />}
            </DropdownMenuItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
