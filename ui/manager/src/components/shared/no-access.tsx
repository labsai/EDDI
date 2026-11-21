import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import { ShieldOff } from "lucide-react";
import { Button } from "@eddi/ui";
import { AppLink } from "@eddi/app-kit/components/shared/app-link";
import { useAuth, useMayOpenScreen } from "@eddi/app-kit/hooks/use-auth";
import { useWorkforceAccess } from "@eddi/app-kit/hooks/use-workforce-access";
import {
  ROLE_ADMIN,
  ROLE_APPROVER,
  ROLE_EDITOR,
  ROLE_USER,
  ROLE_VIEWER,
  eddiRolesOf,
  homePathForRoles,
  type Capability,
} from "@eddi/app-kit/lib/roles";
import { NAV_SECTIONS, pageLabel } from "@/lib/route-registry";

const ROLE_KEYS: Record<string, [key: string, fallback: string]> = {
  [ROLE_ADMIN]: ["access.role.admin", "administrator"],
  [ROLE_EDITOR]: ["access.role.editor", "editor"],
  [ROLE_USER]: ["access.role.user", "user"],
  [ROLE_VIEWER]: ["access.role.viewer", "viewer"],
  [ROLE_APPROVER]: ["access.role.approver", "approver"],
};

/** The translated, human name of an EDDI role ("editor"). */
function useRoleName(): (role: string) => string {
  const { t } = useTranslation();
  return (role) => {
    const entry = ROLE_KEYS[role];
    return entry ? t(entry[0], entry[1]) : role;
  };
}

interface NoAccessProps {
  /** The roles that may use the page — named in the message. */
  needed: readonly string[];
  /** The capability behind the page, to give it a more specific explanation. */
  capability?: Capability | null;
}

/**
 * Shown in place of a page the signed-in role cannot use. It names the role the
 * user holds and the role the page needs, and offers the way back: the first
 * screen the role can open, and Workforce where that applies. The backend would
 * answer 403 to every request the page makes; this replaces a page of toasts
 * with one sentence.
 */
export function NoAccess({ needed, capability }: NoAccessProps) {
  const { t } = useTranslation();
  const { roles } = useAuth();
  // A screen reader hears where it landed: focus moves to the heading when the
  // page appears (a whole-panel role="alert" re-announced everything on re-render).
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus();
  }, []);
  const roleName = useRoleName();
  const mayOpen = useMayOpenScreen();
  const { canAuthor } = useWorkforceAccess();

  const list = (values: string[]) => values.join(" / ");
  const held = eddiRolesOf(roles).map(roleName);
  const heldText = held.length > 0 ? list(held) : t("access.role.none", "no role");
  const neededText = list(needed.map(roleName));

  // The same per-role landing as the chooser (homePathForRoles), so a viewer is
  // sent to the cluster console and an approver to approvals, not to whatever the
  // first open entry of the sidebar happens to be.
  const homePath = homePathForRoles(roles);
  const home = NAV_SECTIONS.flatMap((s) => s.items).find((item) => item.path === homePath && mayOpen(item.path));
  const mayUseWorkforce = canAuthor || roles.includes(ROLE_USER);
  const homeLabel = home ? pageLabel(t, home) : "";

  return (
    <div
      data-testid="no-access"
      className="mx-auto flex max-w-xl flex-col items-center rounded-xl border-2 border-dashed border-border px-6 py-16 text-center"
    >
      <ShieldOff className="h-12 w-12 text-muted-foreground/50" aria-hidden="true" />
      <h1
        ref={headingRef}
        tabIndex={-1}
        data-testid="no-access-title"
        className="mt-4 text-lg font-medium text-foreground outline-none"
      >
        {t("access.title", "Not available for your role")}
      </h1>
      <p className="mt-2 text-sm text-muted-foreground" data-testid="no-access-roles">
        {t("access.body", "You are signed in as {{held}}. This page needs the {{needed}} role.", {
          held: heldText,
          needed: neededText,
        })}
      </p>
      {capability === "setup.guided" && (
        <p className="mt-2 text-sm text-muted-foreground" data-testid="no-access-guided-setup">
          {t(
            "access.guidedSetup",
            "Guided setup stores API keys in the vault, so it needs an administrator. As an editor you can create an agent with Quick Create on the Agents page and configure it afterwards.",
          )}
        </p>
      )}
      <div className="mt-6 flex flex-wrap justify-center gap-2">
        {capability === "setup.guided" && (
          <Button asChild data-testid="no-access-quick-create">
            <AppLink to="/manage/agents">{t("access.quickCreate", "Open Agents (Quick Create)")}</AppLink>
          </Button>
        )}
        {home && capability !== "setup.guided" && (
          <Button asChild data-testid="no-access-home">
            <AppLink to={home.path}>{t("access.goTo", "Go to {{page}}", { page: homeLabel })}</AppLink>
          </Button>
        )}
        {mayUseWorkforce && (
          <Button asChild variant="outline" data-testid="no-access-workforce">
            <AppLink to="/workforce">{t("access.openWorkforce", "Open Workforce")}</AppLink>
          </Button>
        )}
      </div>
    </div>
  );
}
